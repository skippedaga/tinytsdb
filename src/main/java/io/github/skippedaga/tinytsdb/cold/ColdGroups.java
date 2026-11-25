package io.github.skippedaga.tinytsdb.cold;

import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.MemoryCounterStore;
import io.github.skippedaga.tinytsdb.Parts;
import io.github.skippedaga.tinytsdb.SeriesGroup;
import io.github.skippedaga.tinytsdb.StoreSink;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Groups of series (the series of one value of one label, such as one user's) not active for {@code coldAfter} leave
 * memory whole: their buckets go to one row of a {@link ColdStore}, and only a mark (group → when evicted) stays until
 * the group is back or {@code keepFor} has passed.
 *
 * <ul>
 *   <li><b>Active</b>: the application calls {@link #written} for a write and {@link #read} for a read of a group.</li>
 *   <li><b>Evict</b> ({@link #evict}, from the thread that writes): the rows first; then, per group under the write
 *       lock, the series are removed only if none was written since they were exported
 *       ({@link MemoryCounterStore#removeGroup}), and the mark is set. A read inside {@link #reading} never sees the
 *       series gone without the mark.</li>
 *   <li><b>Read of a cold group</b>: {@link #ensureLoaded} reads the row (outside every lock: a slow store never holds
 *       back an eviction or the reads behind it) and adds its buckets back; the store's exception is passed on.</li>
 *   <li><b>Write to a cold group</b>: counted in memory at once (buckets are sums, so the row can be added before or
 *       after); a background thread adds the row.</li>
 *   <li><b>Several processes on one store</b>, each evicting on its own: they hold the same buckets when they count
 *       the same writes, so either's row is right. A row evicted more than {@code conflictAfter} after this process's
 *       mark is of a later absence (another process took the group back, counted more and evicted it again while this
 *       one could not load it): its buckets may hold writes counted here since, which then count twice; counted in
 *       {@link Stats#conflicts} and passed to {@code warn}.</li>
 * </ul>
 *
 * <p>Everything in the series goes with them, the grains not exported too: export every grain the application cannot
 * get back elsewhere ({@link Builder#grains}, every grain by default).</p>
 */
public final class ColdGroups implements AutoCloseable {

    private final ColdStore store;
    private final Supplier<MemoryCounterStore> memory;
    private final String label;
    private final Clock clock;
    private final Duration coldAfter;
    private final Duration keepFor;
    private final Duration conflictAfter;
    private final Set<Grain> grains;
    private final int batch;
    private final Consumer<String> warn;

    /** Group → last write or read, for the groups held. */
    private final ConcurrentHashMap<String, Long> activity = new ConcurrentHashMap<>();
    /** Group → when this process evicted it, for the groups whose buckets are in the store and not in memory. */
    private final ConcurrentHashMap<String, Mark> marks = new ConcurrentHashMap<>();
    /** Write: an eviction's removal and mark; read: a load's write and a read of the counters. */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LinkedBlockingQueue<String> toLoad = new LinkedBlockingQueue<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong loaded = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong kept = new AtomicLong();
    private volatile boolean running = true;
    private final Thread loader;

    private static final class Mark {
        final long evictedAtMs;
        boolean loaded;

        Mark(long evictedAtMs) {
            this.evictedAtMs = evictedAtMs;
        }
    }

    private ColdGroups(Builder b) {
        this.store = b.store;
        this.memory = b.memory;
        this.label = b.label;
        this.clock = b.clock;
        this.coldAfter = b.coldAfter;
        this.keepFor = b.keepFor;
        this.conflictAfter = b.conflictAfter != null ? b.conflictAfter : coldAfter.dividedBy(7).multipliedBy(6);
        this.grains = Set.copyOf(b.grains);
        this.batch = b.batch;
        this.warn = b.warn;
        this.loader = new Thread(this::loadLoop, b.threadName);
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * @param store  where the rows go
     * @param memory the store held (a supplier: an application may swap it, as on a restore)
     * @param label  the label whose values are the groups, such as {@code user}
     */
    public static Builder builder(ColdStore store, Supplier<MemoryCounterStore> memory, String label) {
        return new Builder(store, memory, label);
    }

    public static final class Builder {
        private final ColdStore store;
        private final Supplier<MemoryCounterStore> memory;
        private final String label;
        private Clock clock = Clock.systemUTC();
        private Duration coldAfter;
        private Duration keepFor;
        private Duration conflictAfter;
        private Set<Grain> grains = EnumSet.allOf(Grain.class);
        private int batch = 500;
        private Consumer<String> warn = s -> { };
        private String threadName = "tinytsdb-cold-loader";

        private Builder(ColdStore store, Supplier<MemoryCounterStore> memory, String label) {
            this.store = Objects.requireNonNull(store, "store");
            this.memory = Objects.requireNonNull(memory, "memory");
            this.label = Objects.requireNonNull(label, "label");
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /** Required: a group neither written nor read for this long is evicted. */
        public Builder coldAfter(Duration coldAfter) {
            this.coldAfter = coldAfter;
            return this;
        }

        /**
         * Required: a mark and its row are dropped this long after the eviction. Choose it so that nothing of the
         * group is wanted by then (for windows up to N days: {@code keepFor >= N} days).
         */
        public Builder keepFor(Duration keepFor) {
            this.keepFor = keepFor;
            return this;
        }

        /**
         * A row evicted this long after this process's mark is taken for a later absence (a conflict). Below
         * {@code coldAfter} (a group evicted again was idle that long after its load); 6/7 of it by default.
         */
        public Builder conflictAfter(Duration conflictAfter) {
            this.conflictAfter = conflictAfter;
            return this;
        }

        /** The grains a row keeps (every grain by default); the others are lost with the series. */
        public Builder grains(Set<Grain> grains) {
            if (grains.isEmpty()) {
                throw new IllegalArgumentException("grains must not be empty");
            }
            this.grains = EnumSet.copyOf(grains);
            return this;
        }

        /** Groups per store call (500 by default). */
        public Builder batch(int batch) {
            if (batch < 1) {
                throw new IllegalArgumentException("batch must be positive");
            }
            this.batch = batch;
            return this;
        }

        /** Where the warnings go (a load or an eviction failed, a conflict). */
        public Builder warn(Consumer<String> warn) {
            this.warn = Objects.requireNonNull(warn);
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = Objects.requireNonNull(threadName);
            return this;
        }

        public ColdGroups build() {
            if (coldAfter == null || coldAfter.isNegative() || coldAfter.isZero()) {
                throw new IllegalArgumentException("coldAfter must be positive");
            }
            if (keepFor == null || keepFor.compareTo(coldAfter) < 0) {
                throw new IllegalArgumentException("keepFor must be set and not below coldAfter");
            }
            if (conflictAfter != null && conflictAfter.compareTo(coldAfter) >= 0) {
                throw new IllegalArgumentException("conflictAfter must be below coldAfter");
            }
            return new ColdGroups(this);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // activity
    // ---------------------------------------------------------------------------------------------------------------

    /** A write to {@code group} was applied: a cold group's row is loaded in the background. */
    public void written(String group) {
        activity.put(group, clock.millis());
        if (marks.containsKey(group) && queued.add(group)) {
            toLoad.add(group);
        }
    }

    /** {@code group} was read: a group held stays held. */
    public void read(String group) {
        activity.computeIfPresent(group, (g, t) -> clock.millis());
    }

    public boolean isCold(String group) {
        return marks.containsKey(group);
    }

    /** Runs a read of the counters so that no eviction removes series in the middle of it. */
    public <T> T reading(Supplier<T> read) {
        lock.readLock().lock();
        try {
            return read.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // loading back
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Brings a cold group's buckets back before a read; nothing for a group held. Call it outside {@link #reading}.
     *
     * @throws RuntimeException the store's, when the row cannot be read
     */
    public void ensureLoaded(String group) {
        Mark m = marks.get(group);
        if (m == null) {
            return;
        }
        ColdStore.Row row;
        try {
            row = store.readMany(List.of(group)).get(group);
        } catch (RuntimeException e) {
            loadFailures.incrementAndGet();
            throw e;
        }
        load(group, m, row);
    }

    /** Adds the row read for a mark to memory, once; under the read lock, then the mark's. */
    private void load(String group, Mark m, ColdStore.Row row) {
        lock.readLock().lock();
        try {
            synchronized (m) {
                if (m.loaded) {
                    return;
                }
                if (row != null) {
                    if (row.evictedAtMs() > m.evictedAtMs + conflictAfter.toMillis()) {
                        conflicts.incrementAndGet();
                        warn.accept("Cold group " + label + "=" + group + ": the row was evicted at "
                                + row.evictedAtMs() + ", long after this process's mark " + m.evictedAtMs
                                + "; writes counted here since may count twice");
                    }
                    memory.get().restore(GroupCodec.decode(label, group, row.data()).series(), clock.millis());
                }
                activity.put(group, clock.millis());
                marks.remove(group, m);
                m.loaded = true;
                loaded.incrementAndGet();
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    private void loadLoop() {
        long backoff = 500;
        while (running) {
            String first;
            try {
                first = toLoad.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (first == null) {
                continue;
            }
            List<String> groups = new ArrayList<>();
            groups.add(first);
            toLoad.drainTo(groups, batch - 1);
            try {
                Map<String, ColdStore.Row> rows = store.readMany(groups);
                for (String g : groups) {
                    queued.remove(g);
                    Mark m = marks.get(g);
                    if (m != null) {
                        load(g, m, rows.get(g));
                    }
                }
                backoff = 500;
            } catch (RuntimeException e) {
                loadFailures.incrementAndGet();
                warn.accept("Loading " + groups.size() + " cold groups failed, again in " + backoff + " ms: " + e);
                toLoad.addAll(groups);
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30_000);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // eviction
    // ---------------------------------------------------------------------------------------------------------------

    /** {@link #evict(int)} with no limit. */
    public int evict() {
        return evict(Integer.MAX_VALUE);
    }

    /**
     * Evicts the groups neither written nor read for {@code coldAfter}, at most {@code maxGroups}, and forgets the
     * marks (and rows) older than {@code keepFor}.
     *
     * @return how many groups were evicted (a group with nothing in the grains kept leaves memory without a row
     * or a mark, and is not counted)
     */
    public int evict(int maxGroups) {
        long now = clock.millis();
        long cutoff = now - coldAfter.toMillis();
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, Long> e : activity.entrySet()) {
            if (candidates.size() >= maxGroups) {
                break;
            }
            if (e.getValue() < cutoff && !marks.containsKey(e.getKey())) {
                candidates.add(e.getKey());
            }
        }
        int n = 0;
        for (int from = 0; from < candidates.size(); from += batch) {
            int done = evict(candidates.subList(from, Math.min(candidates.size(), from + batch)), now, cutoff);
            if (done < 0) {
                break;
            }
            n += done;
        }
        forget(now);
        return n;
    }

    /** One batch: the rows, then per group the removal and the mark. -1 when the store refused the rows. */
    private int evict(List<String> part, long now, long cutoff) {
        MemoryCounterStore mem = memory.get();
        List<SeriesGroup> withRow = new ArrayList<>(part.size());
        List<SeriesGroup> empty = new ArrayList<>();
        List<ColdStore.Row> rows = new ArrayList<>(part.size());
        for (String g : part) {
            SeriesGroup group = mem.exportGroup(label, g, grains);
            if (group.isEmpty()) {
                empty.add(group); // nothing in the grains kept: no row, no mark
            } else {
                withRow.add(group);
                rows.add(new ColdStore.Row(g, now, GroupCodec.encode(group)));
            }
        }
        try {
            store.saveAll(rows);
        } catch (RuntimeException e) {
            warn.accept("Evicting cold groups stopped, the store refused the rows: " + e);
            return -1;
        }
        int n = 0;
        lock.writeLock().lock();
        try {
            for (List<SeriesGroup> groups : List.of(withRow, empty)) {
                for (SeriesGroup group : groups) {
                    String g = group.value();
                    Long t = activity.get(g);
                    // written or read since it was picked, or a series written since the export: it stays
                    if (t == null || t >= cutoff || !mem.removeGroup(group)) {
                        kept.incrementAndGet();
                        continue;
                    }
                    activity.remove(g);
                    if (groups == withRow) {
                        marks.put(g, new Mark(now));
                        n++;
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        evicted.addAndGet(n);
        return n;
    }

    private void forget(long now) {
        long before = now - keepFor.toMillis();
        int n = 0;
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            if (e.getValue().evictedAtMs < before && marks.remove(e.getKey(), e.getValue())) {
                n++;
            }
        }
        expired.addAndGet(n);
        try {
            store.deleteEvictedBefore(before);
        } catch (RuntimeException e) {
            warn.accept("Dropping old cold groups failed: " + e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // state
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A store restored without this state (no activity known): each group is taken as active at its newest bucket of
     * a grain but all time (a group with only all-time buckets: as long idle), so the groups idle for
     * {@code coldAfter} go at the first eviction.
     */
    public void initFrom(MemoryCounterStore mem) {
        activity.clear();
        marks.clear();
        long now = clock.millis();
        Map<String, Long> newest = new HashMap<>();
        mem.export(new StoreSink() {
            @Override
            public void bucket(Labels series, Grain grain, long start, Parts parts) {
                String g = series.get(label);
                if (!g.isEmpty() && grain != Grain.ALL) {
                    newest.merge(g, Math.min(start, now), Math::max);
                } else if (!g.isEmpty()) {
                    newest.putIfAbsent(g, Long.MIN_VALUE);
                }
            }

            @Override
            public void firstAdded(Labels series, long occurredAtMs) {
            }

            @Override
            public void claim(String key, long expiresAtMs) {
            }
        });
        activity.putAll(newest);
    }

    /** The activity and the marks, for a snapshot next to the store's. */
    public void writeState(DataOutputStream out) throws IOException {
        Map<String, Long> a = new HashMap<>(activity);
        out.writeInt(a.size());
        for (Map.Entry<String, Long> e : a.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeLong(e.getValue());
        }
        Map<String, Mark> m = new HashMap<>(marks);
        out.writeInt(m.size());
        for (Map.Entry<String, Mark> e : m.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeLong(e.getValue().evictedAtMs);
        }
    }

    /** The state {@link #writeState} wrote; call it after the store it goes with is in place. */
    public void readState(DataInputStream in) throws IOException {
        activity.clear();
        marks.clear();
        toLoad.clear();
        queued.clear();
        int a = in.readInt();
        for (int i = 0; i < a; i++) {
            activity.put(in.readUTF(), in.readLong());
        }
        int m = in.readInt();
        for (int i = 0; i < m; i++) {
            marks.put(in.readUTF(), new Mark(in.readLong()));
        }
        // a write to a cold group before the snapshot left buckets in memory: they get their row now
        MemoryCounterStore mem = memory.get();
        for (String g : marks.keySet()) {
            if (!mem.seriesOf(label, g).isEmpty() && queued.add(g)) {
                toLoad.add(g);
            }
        }
    }

    /** Forgets everything: the application holds every group again (a replay from the start). */
    public void clear() {
        activity.clear();
        marks.clear();
        toLoad.clear();
        queued.clear();
    }

    /** The groups held (with activity). */
    public Set<String> active() {
        return activity.keySet();
    }

    /** The groups held that were written or read at {@code sinceMs} or later. */
    public List<String> activeSince(long sinceMs) {
        List<String> out = new ArrayList<>();
        activity.forEach((g, t) -> {
            if (t >= sinceMs) {
                out.add(g);
            }
        });
        return out;
    }

    /**
     * @param active       groups held
     * @param cold         groups evicted, not loaded back
     * @param loadQueue    cold groups written to, waiting for their row
     * @param evicted      groups evicted so far
     * @param loaded       groups loaded back so far
     * @param loadFailures store reads that failed
     * @param conflicts    rows of a later absence (see the class comment)
     * @param expired      marks dropped after {@code keepFor}
     * @param kept         groups picked for eviction that stayed: written or read meanwhile
     */
    public record Stats(int active, int cold, int loadQueue, long evicted, long loaded, long loadFailures,
                        long conflicts, long expired, long kept) {
    }

    public Stats stats() {
        return new Stats(activity.size(), marks.size(), toLoad.size(), evicted.get(), loaded.get(),
                loadFailures.get(), conflicts.get(), expired.get(), kept.get());
    }

    /** Stops the background loader. */
    @Override
    public void close() {
        running = false;
        loader.interrupt();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
