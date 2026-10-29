package io.github.skippedaga.tinytsdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The in-process {@link CounterStore}: sorted primitive arrays per series and grain, an inverted label index, and a
 * binary snapshot format ({@link #writeSnapshot} / {@link #readSnapshot}). Thread-safe: writes take a write lock,
 * reads a read lock.
 */
public final class MemoryCounterStore implements CounterStore {

    private static final int SNAPSHOT_MAGIC = 0x54545344; // "TTSD"
    private static final int SNAPSHOT_VERSION = 1;
    private static final Grain[] GRAINS = Grain.values();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<Labels, SeriesData> series = new HashMap<>();
    // label name → value → series; the Postings hold the one shared copy of the value string
    private final Map<String, Map<String, Postings>> postings = new HashMap<>();
    private final Map<String, String> labelNames = new HashMap<>();
    private final Map<String, Long> claims = new HashMap<>();

    @Override
    public boolean apply(Batch batch) {
        lock.writeLock().lock();
        try {
            if (batch.claimKey() != null) {
                if (claims.containsKey(batch.claimKey())) {
                    return false;
                }
                claims.put(batch.claimKey(), batch.claimExpiresAtMs());
            }
            Set<SeriesData> touched = new HashSet<>();
            for (SeriesWrite w : batch.series()) {
                SeriesData d = getOrCreate(w.series());
                if (touched.add(d)) {
                    d.version++;
                }
                d.lastWriteMs = Math.max(d.lastWriteMs, w.writtenAtMs());
                if (w.positive()) {
                    d.firstAdded = Math.min(d.firstAdded, w.occurredAtMs());
                }
            }
            for (BucketWrite w : batch.buckets()) {
                SeriesData d = getOrCreate(w.series());
                if (touched.add(d)) {
                    d.version++;
                }
                d.grains.computeIfAbsent(w.grain(), g -> new BucketArray()).add(w.start(), w.delta());
            }
            for (SeriesData d : touched) {
                patchRings(d, batch.buckets());
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void patchRings(SeriesData d, List<BucketWrite> writes) {
        if (d.rings == null) {
            return;
        }
        for (Map.Entry<String, RingSnapshot> e : d.rings.entrySet()) {
            RingSnapshot r = e.getValue();
            for (BucketWrite w : writes) {
                if (w.grain() == r.grain() && w.series().equals(d.labels)) {
                    r = r.patch(w.start(), w.delta(), d.version);
                }
            }
            e.setValue(new RingSnapshot(r.grain(), r.steps(), r.fromStart(), r.currentStart(), r.sum(), d.version));
        }
    }

    @Override
    public void releaseClaim(String claimKey) {
        lock.writeLock().lock();
        try {
            claims.remove(claimKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Parts read(Labels labels, Grain grain, long start) {
        lock.readLock().lock();
        try {
            BucketArray a = array(labels, grain);
            return a == null ? Parts.ZERO : a.get(start);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Parts sum(Labels labels, Grain grain, long fromStart, long toStart) {
        lock.readLock().lock();
        try {
            BucketArray a = array(labels, grain);
            return a == null ? Parts.ZERO : a.sum(fromStart, toStart);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public SortedMap<Long, Parts> buckets(Labels labels, Grain grain, long fromStart, long toStart) {
        lock.readLock().lock();
        try {
            BucketArray a = array(labels, grain);
            return a == null ? new TreeMap<>() : a.range(fromStart, toStart);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public OptionalLong firstAdded(Labels labels) {
        lock.readLock().lock();
        try {
            SeriesData d = series.get(labels);
            return d == null || d.firstAdded == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(d.firstAdded);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long version(Labels labels) {
        lock.readLock().lock();
        try {
            SeriesData d = series.get(labels);
            return d == null ? 0 : d.version;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Labels> series(List<Matcher> matchers, int limit) {
        lock.readLock().lock();
        try {
            Collection<SeriesData> candidates = null;
            for (Matcher m : matchers) {
                if (m.type() == Matcher.Type.EQUAL && !m.value().isEmpty()) {
                    Map<String, Postings> byValue = postings.get(m.name());
                    Postings p = byValue == null ? null : byValue.get(m.value());
                    if (p == null) {
                        return List.of();
                    }
                    if (candidates == null || p.series.size() < candidates.size()) {
                        candidates = p.series;
                    }
                }
            }
            if (candidates == null) {
                candidates = series.values();
            }
            List<Labels> out = new ArrayList<>();
            for (SeriesData d : candidates) {
                if (matchesAll(d.labels, matchers)) {
                    out.add(d.labels);
                    if (out.size() > limit) {
                        break;
                    }
                }
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean matchesAll(Labels labels, List<Matcher> matchers) {
        for (Matcher m : matchers) {
            if (!m.matches(labels.get(m.name()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<String> labelValues(String label) {
        lock.readLock().lock();
        try {
            Map<String, Postings> byValue = postings.get(label);
            return byValue == null ? Set.of() : Set.copyOf(byValue.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public RingSnapshot snapshot(Labels labels, String key) {
        lock.readLock().lock();
        try {
            SeriesData d = series.get(labels);
            return d == null || d.rings == null ? null : d.rings.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean putSnapshot(Labels labels, String key, RingSnapshot snapshot) {
        lock.writeLock().lock();
        try {
            SeriesData d = series.get(labels);
            if (d == null) {
                return snapshot.version() == 0;
            }
            if (d.version != snapshot.version()) {
                return false;
            }
            if (d.rings == null) {
                d.rings = new HashMap<>(4);
            }
            RingSnapshot existing = d.rings.get(key);
            if (existing != null && existing.currentStart() > snapshot.currentStart()) {
                return false;
            }
            d.rings.put(key, snapshot);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void expire(Grain grain, long beforeStart) {
        if (grain == Grain.ALL) {
            throw new IllegalArgumentException("All-time buckets do not expire");
        }
        lock.writeLock().lock();
        try {
            for (SeriesData d : series.values()) {
                BucketArray a = d.grains.get(grain);
                if (a != null) {
                    a.expire(beforeStart);
                    if (a.size == 0) {
                        d.grains.remove(grain);
                    }
                }
                if (d.rings != null) {
                    d.rings.values().removeIf(r -> r.grain() == grain && r.fromStart() < beforeStart);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int expireClaims(long nowMs) {
        lock.writeLock().lock();
        try {
            int before = claims.size();
            claims.values().removeIf(expiresAt -> expiresAt <= nowMs);
            return before - claims.size();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int removeIdle(String label, long lastWriteBeforeMs) {
        lock.writeLock().lock();
        try {
            int removed = 0;
            for (Iterator<SeriesData> it = series.values().iterator(); it.hasNext(); ) {
                SeriesData d = it.next();
                if (d.labels.has(label) && d.lastWriteMs < lastWriteBeforeMs) {
                    it.remove();
                    unindex(d);
                    removed++;
                }
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void export(StoreSink sink) {
        lock.readLock().lock();
        try {
            for (SeriesData d : series.values()) {
                for (Map.Entry<Grain, BucketArray> e : d.grains.entrySet()) {
                    BucketArray a = e.getValue();
                    for (int i = 0; i < a.size; i++) {
                        sink.bucket(d.labels, e.getKey(), a.start[i], new Parts(a.added[i], a.retracted(i)));
                    }
                }
                if (d.firstAdded != Long.MAX_VALUE) {
                    sink.firstAdded(d.labels, d.firstAdded);
                }
            }
            claims.forEach(sink::claim);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public StoreSink mergeMaxSink() {
        return new StoreSink() {
            @Override
            public void bucket(Labels labels, Grain grain, long start, Parts parts) {
                lock.writeLock().lock();
                try {
                    SeriesData d = getOrCreate(labels);
                    d.version++;
                    d.rings = null; // a max is not a delta: stored aggregates cannot be patched
                    d.grains.computeIfAbsent(grain, g -> new BucketArray()).max(start, parts);
                } finally {
                    lock.writeLock().unlock();
                }
            }

            @Override
            public void firstAdded(Labels labels, long occurredAtMs) {
                lock.writeLock().lock();
                try {
                    SeriesData d = getOrCreate(labels);
                    d.firstAdded = Math.min(d.firstAdded, occurredAtMs);
                } finally {
                    lock.writeLock().unlock();
                }
            }

            @Override
            public void claim(String key, long expiresAtMs) {
                lock.writeLock().lock();
                try {
                    claims.merge(key, expiresAtMs, Math::max);
                } finally {
                    lock.writeLock().unlock();
                }
            }
        };
    }

    /** How many series are held. */
    public int seriesCount() {
        lock.readLock().lock();
        try {
            return series.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** How many buckets are held, over every series and grain. */
    public long bucketCount() {
        lock.readLock().lock();
        try {
            long n = 0;
            for (SeriesData d : series.values()) {
                for (BucketArray a : d.grains.values()) {
                    n += a.size;
                }
            }
            return n;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int claimCount() {
        lock.readLock().lock();
        try {
            return claims.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // snapshot
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Writes every series, anchor and claim to {@code out} (not closed, not compressed: wrap it in a GZIPOutputStream
     * for that). Rolling-window aggregates are left out; they are rebuilt on the next read. Takes the read lock, so
     * writes wait and reads do not.
     */
    public void writeSnapshot(OutputStream out) throws IOException {
        lock.readLock().lock();
        try {
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
            data.writeInt(SNAPSHOT_MAGIC);
            data.writeInt(SNAPSHOT_VERSION);

            Map<String, Integer> strings = new HashMap<>();
            List<String> table = new ArrayList<>();
            for (SeriesData d : series.values()) {
                for (int i = 0; i < d.labels.size(); i++) {
                    intern(d.labels.name(i), strings, table);
                    intern(d.labels.value(i), strings, table);
                }
            }
            data.writeInt(table.size());
            for (String s : table) {
                writeString(data, s);
            }

            data.writeInt(series.size());
            for (SeriesData d : series.values()) {
                data.writeInt(d.labels.size());
                for (int i = 0; i < d.labels.size(); i++) {
                    data.writeInt(strings.get(d.labels.name(i)));
                    data.writeInt(strings.get(d.labels.value(i)));
                }
                data.writeLong(d.firstAdded);
                data.writeLong(d.lastWriteMs);
                data.writeInt(d.grains.size());
                for (Map.Entry<Grain, BucketArray> e : d.grains.entrySet()) {
                    BucketArray a = e.getValue();
                    data.writeByte(e.getKey().ordinal());
                    data.writeInt(a.size);
                    for (int i = 0; i < a.size; i++) {
                        data.writeLong(a.start[i]);
                        data.writeLong(a.added[i]);
                        data.writeLong(a.retracted(i));
                    }
                }
            }
            data.writeInt(claims.size());
            for (Map.Entry<String, Long> e : claims.entrySet()) {
                writeString(data, e.getKey());
                data.writeLong(e.getValue());
            }
            data.flush();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Reads a store written by {@link #writeSnapshot}. Buffers, so it may read past the end of the snapshot: give it
     * a stream that holds only the snapshot.
     *
     * @throws IOException on a stream that is not a snapshot or of another version
     */
    public static MemoryCounterStore readSnapshot(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        if (data.readInt() != SNAPSHOT_MAGIC) {
            throw new IOException("Not a tinytsdb snapshot");
        }
        int version = data.readInt();
        if (version != SNAPSHOT_VERSION) {
            throw new IOException("Snapshot version " + version + ", this build reads " + SNAPSHOT_VERSION);
        }
        MemoryCounterStore store = new MemoryCounterStore();
        String[] table = new String[data.readInt()];
        for (int i = 0; i < table.length; i++) {
            table[i] = readString(data);
        }
        int count = data.readInt();
        for (int n = 0; n < count; n++) {
            int size = data.readInt();
            Map<String, String> map = new HashMap<>(size * 2);
            for (int i = 0; i < size; i++) {
                map.put(table[data.readInt()], table[data.readInt()]);
            }
            SeriesData d = store.getOrCreate(Labels.of(map));
            d.firstAdded = data.readLong();
            d.lastWriteMs = data.readLong();
            d.version = 1;
            int grains = data.readInt();
            for (int g = 0; g < grains; g++) {
                int ordinal = data.readByte();
                if (ordinal < 0 || ordinal >= GRAINS.length) {
                    throw new IOException("Unknown grain " + ordinal);
                }
                int buckets = data.readInt();
                BucketArray a = new BucketArray(buckets);
                for (int i = 0; i < buckets; i++) {
                    a.start[i] = data.readLong();
                    a.added[i] = data.readLong();
                    a.setRetractedAt(i, data.readLong());
                }
                a.size = buckets;
                d.grains.put(GRAINS[ordinal], a);
            }
        }
        int claimCount = data.readInt();
        for (int i = 0; i < claimCount; i++) {
            store.claims.put(readString(data), data.readLong());
        }
        return store;
    }

    private static void intern(String s, Map<String, Integer> strings, List<String> table) {
        if (!strings.containsKey(s)) {
            strings.put(s, table.size());
            table.add(s);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Corrupt snapshot: negative string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------------------------------

    private BucketArray array(Labels labels, Grain grain) {
        SeriesData d = series.get(labels);
        return d == null ? null : d.grains.get(grain);
    }

    /** Under the write lock. Shares label strings with the series already held. */
    private SeriesData getOrCreate(Labels labels) {
        SeriesData d = series.get(labels);
        if (d != null) {
            return d;
        }
        String[] names = new String[labels.size()];
        String[] values = new String[labels.size()];
        List<Postings> lists = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            String name = labelNames.computeIfAbsent(labels.name(i), n -> n);
            Postings p = postings.computeIfAbsent(name, k -> new HashMap<>())
                    .computeIfAbsent(labels.value(i), Postings::new);
            names[i] = name;
            values[i] = p.value;
            lists.add(p);
        }
        d = new SeriesData(labels.withStrings(names, values));
        series.put(d.labels, d);
        for (Postings p : lists) {
            p.series.add(d);
        }
        return d;
    }

    private void unindex(SeriesData d) {
        for (int i = 0; i < d.labels.size(); i++) {
            Map<String, Postings> byValue = postings.get(d.labels.name(i));
            Postings p = byValue.get(d.labels.value(i));
            p.series.remove(d);
            if (p.series.isEmpty()) {
                byValue.remove(d.labels.value(i));
                if (byValue.isEmpty()) {
                    postings.remove(d.labels.name(i));
                    labelNames.remove(d.labels.name(i));
                }
            }
        }
    }

    private static final class Postings {
        final String value;
        final Set<SeriesData> series = new HashSet<>();

        Postings(String value) {
            this.value = value;
        }
    }

    private static final class SeriesData {
        final Labels labels;
        final EnumMap<Grain, BucketArray> grains = new EnumMap<>(Grain.class);
        long firstAdded = Long.MAX_VALUE;
        long lastWriteMs = Long.MIN_VALUE;
        long version;
        Map<String, RingSnapshot> rings;

        SeriesData(Labels labels) {
            this.labels = labels;
        }
    }

    /**
     * Buckets of one grain of one series, sorted by start; only non-empty ones. The retracted parts get an array only
     * once one is not zero: most counters never retract.
     */
    static final class BucketArray {
        long[] start;
        long[] added;
        long[] retracted; // null while every retracted part is 0
        int size;

        BucketArray() {
            this(2);
        }

        BucketArray(int capacity) {
            start = new long[capacity];
            added = new long[capacity];
        }

        long retracted(int i) {
            return retracted == null ? 0 : retracted[i];
        }

        void setRetractedAt(int i, long value) {
            setRetracted(i, value);
        }

        private void setRetracted(int i, long value) {
            if (retracted == null) {
                if (value == 0) {
                    return;
                }
                retracted = new long[start.length];
            }
            retracted[i] = value;
        }

        void add(long s, Parts delta) {
            int i = indexFor(s);
            added[i] = Math.addExact(added[i], delta.added());
            if (delta.retracted() != 0) {
                setRetracted(i, Math.addExact(retracted(i), delta.retracted()));
            }
        }

        void max(long s, Parts parts) {
            int i = indexFor(s);
            added[i] = Math.max(added[i], parts.added());
            setRetracted(i, Math.max(retracted(i), parts.retracted()));
        }

        Parts get(long s) {
            int i = Arrays.binarySearch(start, 0, size, s);
            return i < 0 ? Parts.ZERO : new Parts(added[i], retracted(i));
        }

        Parts sum(long from, long to) {
            long a = 0;
            long r = 0;
            for (int i = lowerBound(from); i < size && start[i] < to; i++) {
                a = Math.addExact(a, added[i]);
                r = Math.addExact(r, retracted(i));
            }
            return a == 0 && r == 0 ? Parts.ZERO : new Parts(a, r);
        }

        SortedMap<Long, Parts> range(long from, long to) {
            SortedMap<Long, Parts> out = new TreeMap<>();
            for (int i = lowerBound(from); i < size && start[i] < to; i++) {
                out.put(start[i], new Parts(added[i], retracted(i)));
            }
            return out;
        }

        void expire(long before) {
            int n = lowerBound(before);
            if (n == 0) {
                return;
            }
            System.arraycopy(start, n, start, 0, size - n);
            System.arraycopy(added, n, added, 0, size - n);
            if (retracted != null) {
                System.arraycopy(retracted, n, retracted, 0, size - n);
            }
            size -= n;
            if (start.length > 16 && size < start.length / 4) {
                resize(Math.max(2, size * 2));
            }
        }

        private void resize(int capacity) {
            start = Arrays.copyOf(start, capacity);
            added = Arrays.copyOf(added, capacity);
            if (retracted != null) {
                retracted = Arrays.copyOf(retracted, capacity);
            }
        }

        private int indexFor(long s) {
            if (size > 0 && start[size - 1] == s) {
                return size - 1;
            }
            int i = size == 0 || s > start[size - 1] ? -size - 1 : Arrays.binarySearch(start, 0, size, s);
            if (i >= 0) {
                return i;
            }
            i = -i - 1;
            if (size == start.length) {
                resize(Math.max(2, size + (size >> 1) + 1));
            }
            if (i < size) {
                System.arraycopy(start, i, start, i + 1, size - i);
                System.arraycopy(added, i, added, i + 1, size - i);
                if (retracted != null) {
                    System.arraycopy(retracted, i, retracted, i + 1, size - i);
                }
            }
            start[i] = s;
            added[i] = 0;
            if (retracted != null) {
                retracted[i] = 0;
            }
            size++;
            return i;
        }

        /** First index whose start is at least {@code key}. */
        private int lowerBound(long key) {
            int lo = 0;
            int hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (start[mid] < key) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }
}
