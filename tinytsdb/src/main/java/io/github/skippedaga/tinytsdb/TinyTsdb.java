package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketKey;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.Position;
import io.github.skippedaga.tinytsdb.CounterStore.Result;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesBatch;
import io.github.skippedaga.tinytsdb.TsdbListener.ReadKind;
import io.github.skippedaga.tinytsdb.TsdbListener.RingOutcome;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Counters with labels, kept per time bucket at the grains you configure, read over calendar, rolling, anchored and
 * arbitrary windows, and queried with a subset of PromQL.
 *
 * <ul>
 *   <li>Values are PN counters of 64-bit integers ({@link Parts}): a negative delta is a retraction, kept apart.</li>
 *   <li>Every write goes to the bucket of the event's own time, at every grain whose retention still covers it — a
 *       correction months later lands where the event was.</li>
 *   <li>{@link #applyOnce} writes a batch at most once per caller-supplied key; {@link #applyAt} at most once per
 *       series and stream offset.</li>
 *   <li>Storage is a {@link CounterStore}: {@link MemoryCounterStore} by default. Reads of many windows and series go
 *       to the store in one {@link CounterStore#readMany} when it has no {@link RangeStore}.</li>
 * </ul>
 *
 * <p>Thread-safe as far as the store is. A query reads series one after another, so it is not a snapshot across
 * series while writes go on.</p>
 */
public final class TinyTsdb {

    private static final int MAX_ADVANCE_STEPS = 4;
    private static final int RING_ATTEMPTS = 3;

    private final List<Tier> tiers; // finest first
    private final ZoneId zone;
    private final Clock clock;
    private final CounterStore store;
    private final Map<String, LabelTransform> transforms;
    private final List<CardinalityRule> rules;
    private final Map<String, Set<String>> seenValues = new HashMap<>();
    private final TsdbListener listener;
    private final int maxSeriesPerSelector;
    private final Duration claimTtl;
    private final boolean ringSnapshots;

    private TinyTsdb(Builder b) {
        this.tiers = List.copyOf(b.tiers);
        this.zone = b.zone;
        this.clock = b.clock;
        this.store = b.store;
        this.transforms = Map.copyOf(b.transforms);
        this.rules = List.copyOf(b.rules);
        this.listener = b.listener;
        this.maxSeriesPerSelector = b.maxSeriesPerSelector;
        this.claimTtl = b.claimTtl;
        this.ringSnapshots = b.ringSnapshots;
        for (CardinalityRule r : rules) {
            if (r.maxValues() > 0) {
                seenValues.put(r.label(), new HashSet<>(
                        store instanceof IndexedStore indexed ? indexed.labelValues(r.label()) : Set.of()));
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A grain kept, and for how long. */
    public record Tier(Grain grain, Duration retention) {

        public Tier {
            if (grain == Grain.ALL) {
                throw new IllegalArgumentException("ALL is always kept; it is not a tier");
            }
            if (retention.isNegative() || retention.isZero()) {
                throw new IllegalArgumentException("retention must be positive");
            }
        }
    }

    /** The tiers, finest first. */
    public List<Tier> tiers() {
        return tiers;
    }

    public ZoneId zone() {
        return zone;
    }

    public Clock clock() {
        return clock;
    }

    public CounterStore store() {
        return store;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // writes
    // ---------------------------------------------------------------------------------------------------------------

    /** Adds {@code delta} (negative to retract) to one counter at {@code occurredAtMs}. */
    public void add(String metric, Labels labels, long occurredAtMs, long delta) {
        write(List.of(new Increment(metric, labels, occurredAtMs, delta)));
    }

    /** Writes the increments in one call, atomic as far as the store is ({@link CounterStore#atomicity()}). */
    public void write(List<Increment> increments) {
        long t = System.nanoTime();
        store.apply(batch(increments, null, null, 0));
        listener.onWrite(increments.size(), System.nanoTime() - t);
    }

    /**
     * Writes the increments unless {@code claimKey} was claimed before; the claim lasts {@code claimTtl}. The key is
     * the caller's and must name the change, e.g. event id plus version. Keep the TTL at least as long as the source
     * log's retention, or a replay can count again. With a store atomic per series, the claim is taken first and
     * released if the writes fail.
     *
     * @return false for a repeat: nothing was written
     */
    public boolean applyOnce(String claimKey, List<Increment> increments) {
        if (claimKey == null || claimKey.isEmpty()) {
            throw new IllegalArgumentException("claimKey must not be empty");
        }
        long t = System.nanoTime();
        long expires = claimTtl == null ? Long.MAX_VALUE : clock.millis() + claimTtl.toMillis();
        boolean applied;
        if (store.atomicity() == CounterStore.Atomicity.BATCH) {
            applied = !store.apply(batch(increments, null, claimKey, expires)).duplicate();
        } else if (!store.claim(claimKey, expires)) {
            applied = false;
        } else {
            try {
                store.apply(batch(increments, null, null, 0));
                applied = true;
            } catch (RuntimeException e) {
                store.releaseClaim(claimKey);
                throw e;
            }
        }
        if (applied) {
            listener.onWrite(increments.size(), System.nanoTime() - t);
        } else {
            listener.onDuplicate();
        }
        return applied;
    }

    /**
     * Writes the increments of the report at {@code offset} of {@code stream} (a Kafka partition, a log) to every
     * series that has not applied that offset yet. A retry after a partial failure — or a replay — writes only the
     * series that did not take it, whatever else the batch holds; offsets of a stream must only grow.
     */
    public Result applyAt(String stream, long offset, List<Increment> increments) {
        long t = System.nanoTime();
        Result r = store.apply(batch(increments, new Position(stream, offset), null, 0));
        if (r.applied() > 0) {
            listener.onWrite(increments.size(), System.nanoTime() - t);
        }
        if (r.skipped() > 0) {
            listener.onDuplicate();
        }
        return r;
    }

    /** Forgets a claim, so a retry after the caller's own failure is applied. */
    public void releaseClaim(String claimKey) {
        store.releaseClaim(claimKey);
    }

    private Batch batch(List<Increment> increments, Position position, String claimKey, long claimExpiresAt) {
        long now = clock.millis();
        Map<Labels, List<BucketWrite>> buckets = new LinkedHashMap<>();
        Map<Labels, Long> firstAdded = new HashMap<>();
        for (Increment inc : increments) {
            if (inc.delta() == 0) {
                continue;
            }
            Labels s = guard(transform(inc.labels())).with(Labels.METRIC_NAME, inc.metric());
            Parts delta = Parts.of(inc.delta());
            List<BucketWrite> writes = buckets.computeIfAbsent(s, k -> new ArrayList<>(tiers.size() + 1));
            for (Tier tier : tiers) {
                long start = tier.grain().floor(inc.occurredAtMs(), zone);
                if (start >= earliest(tier, now)) {
                    writes.add(new BucketWrite(tier.grain(), start, delta));
                }
            }
            writes.add(new BucketWrite(Grain.ALL, 0, delta));
            if (delta.added() > 0) {
                firstAdded.merge(s, inc.occurredAtMs(), Math::min);
            }
        }
        List<SeriesBatch> series = new ArrayList<>(buckets.size());
        buckets.forEach((s, writes) -> series.add(new SeriesBatch(s, writes,
                firstAdded.getOrDefault(s, Long.MAX_VALUE), now, position)));
        return new Batch(series, claimKey, claimExpiresAt);
    }

    private Labels transform(Labels labels) {
        if (transforms.isEmpty()) {
            return labels;
        }
        Map<String, String> map = labels.toMap();
        boolean changed = false;
        for (Map.Entry<String, LabelTransform> t : transforms.entrySet()) {
            String v = map.get(t.getKey());
            if (v != null) {
                String out = t.getValue().apply(v);
                map.put(t.getKey(), out == null ? "" : out);
                changed = true;
            }
        }
        return changed ? Labels.of(map) : labels;
    }

    private Labels guard(Labels labels) {
        Labels out = labels;
        for (CardinalityRule r : rules) {
            if (r.maxValues() == 0) {
                continue;
            }
            String v = out.get(r.label());
            if (v.isEmpty() || v.equals(r.overflowValue())) {
                continue;
            }
            Set<String> seen = seenValues.get(r.label());
            synchronized (seen) {
                if (seen.contains(v)) {
                    continue;
                }
                if (seen.size() < r.maxValues()) {
                    seen.add(v);
                    continue;
                }
            }
            out = out.with(r.label(), r.overflowValue());
            listener.onOverflow(r.label());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // reads
    // ---------------------------------------------------------------------------------------------------------------

    /** The series {@code metric} with {@code labels} over {@code window}, now. */
    public Reading read(String metric, Labels labels, Window window) {
        return read(metric, labels, window, clock.millis());
    }

    /** The series {@code metric} with {@code labels} over {@code window}, as of {@code asOfMs}. */
    public Reading read(String metric, Labels labels, Window window, long asOfMs) {
        return read(metric, labels, List.of(window), asOfMs).get(window);
    }

    /**
     * The series over several windows in one go: one read of the store for all of them when it has no
     * {@link RangeStore}.
     */
    public Map<Window, Reading> read(String metric, Labels labels, List<Window> windows, long asOfMs) {
        Labels.checkMetricName(metric);
        long t = System.nanoTime();
        Labels series = transform(labels).with(Labels.METRIC_NAME, metric);
        Map<Window, Reading> out = new LinkedHashMap<>();
        Map<Labels, List<Reading>> read = readMany(List.of(series), windows, asOfMs);
        List<Reading> readings = read.get(series);
        for (int i = 0; i < windows.size(); i++) {
            out.put(windows.get(i), readings.get(i));
        }
        listener.onRead(windows.size() == 1 ? kind(windows.get(0)) : ReadKind.RANGE, System.nanoTime() - t);
        return out;
    }

    /** The earliest first-added time over every series of {@code metric} matching {@code matchers}, if any. */
    public OptionalLong firstAdded(String metric, List<Matcher> matchers) {
        Labels.checkMetricName(metric);
        List<Matcher> all = new ArrayList<>(matchers);
        all.add(Matcher.of(Matcher.Type.EQUAL, Labels.METRIC_NAME, metric));
        long min = Long.MAX_VALUE;
        for (Labels s : select(all)) {
            OptionalLong f = store.firstAdded(s);
            if (f.isPresent()) {
                min = Math.min(min, f.getAsLong());
            }
        }
        return min == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(min);
    }

    /** When the series {@code metric} with {@code labels} first had a positive delta, if ever. */
    public OptionalLong firstAdded(String metric, Labels labels) {
        Labels.checkMetricName(metric);
        return store.firstAdded(transform(labels).with(Labels.METRIC_NAME, metric));
    }

    /**
     * Every series of {@code metric} matching {@code filter} (label equality; more labels are allowed), summed per
     * value of the {@code groupBy} labels.
     */
    public Map<Labels, Reading> readGrouped(String metric, Labels filter, List<String> groupBy, Window window) {
        List<Matcher> matchers = new ArrayList<>();
        for (int i = 0; i < filter.size(); i++) {
            matchers.add(Matcher.of(Matcher.Type.EQUAL, filter.name(i), filter.value(i)));
        }
        return readGrouped(metric, matchers, groupBy, window, clock.millis());
    }

    /** As {@link #readGrouped(String, Labels, List, Window)}, with any matchers and an explicit time. */
    public Map<Labels, Reading> readGrouped(String metric, List<Matcher> matchers, List<String> groupBy, Window window,
                                            long asOfMs) {
        return readGrouped(metric, matchers, groupBy, List.of(window), asOfMs).get(window);
    }

    /** Grouped reads of several windows in one go. */
    public Map<Window, Map<Labels, Reading>> readGrouped(String metric, List<Matcher> matchers, List<String> groupBy,
                                                         List<Window> windows, long asOfMs) {
        Labels.checkMetricName(metric);
        long t = System.nanoTime();
        List<Matcher> all = new ArrayList<>(matchers);
        all.add(Matcher.of(Matcher.Type.EQUAL, Labels.METRIC_NAME, metric));
        List<Labels> series = select(all);
        Map<Labels, List<Reading>> read = readMany(series, windows, asOfMs);
        Map<Window, Map<Labels, Reading>> out = new LinkedHashMap<>();
        for (int i = 0; i < windows.size(); i++) {
            Map<Labels, Reading> grouped = new LinkedHashMap<>();
            for (Labels s : series) {
                grouped.merge(s.keep(groupBy), read.get(s).get(i), Reading::plus);
            }
            out.put(windows.get(i), grouped);
        }
        listener.onRead(windows.size() == 1 ? kind(windows.get(0)) : ReadKind.RANGE, System.nanoTime() - t);
        return out;
    }

    private static ReadKind kind(Window w) {
        if (w instanceof Window.Calendar) {
            return ReadKind.CALENDAR;
        }
        if (w instanceof Window.Rolling) {
            return ReadKind.ROLLING;
        }
        if (w instanceof Window.Range) {
            return ReadKind.RANGE;
        }
        if (w instanceof Window.Anchored) {
            return ReadKind.ANCHORED;
        }
        return ReadKind.ALL_TIME;
    }

    /** Buckets with start in {@code [fromStart, toStart)}. */
    private record Span(Grain grain, long fromStart, long toStart) {
    }

    /** A window resolved for one series. */
    private record Plan(List<Span> spans, long from, long to, boolean exact) {
    }

    /**
     * Readings of every series for every window, in window order. Rolling windows at the current step go through
     * their stored aggregates when {@code ringSnapshots} is on; everything else is summed from the buckets — per
     * span with a {@link RangeStore}, else with one {@link CounterStore#readMany} for all series and windows.
     */
    Map<Labels, List<Reading>> readMany(List<Labels> series, List<Window> windows, long asOfMs) {
        long now = clock.millis();
        Map<Labels, List<Reading>> out = new LinkedHashMap<>();
        Map<Labels, Plan[]> plans = new LinkedHashMap<>();
        for (Labels s : series) {
            Plan[] p = new Plan[windows.size()];
            for (int i = 0; i < windows.size(); i++) {
                Window w = windows.get(i);
                p[i] = viaRing(w, asOfMs, now) ? null : plan(s, w, asOfMs, now);
            }
            plans.put(s, p);
        }
        Map<Labels, Map<BucketKey, Parts>> fetched = store instanceof RangeStore ? Map.of() : fetch(plans);
        for (Labels s : series) {
            Plan[] p = plans.get(s);
            List<Reading> readings = new ArrayList<>(windows.size());
            for (int i = 0; i < windows.size(); i++) {
                if (p[i] == null) {
                    Window.Rolling r = (Window.Rolling) windows.get(i);
                    readings.add(ring(s, r.steps(), r.grain(), asOfMs, now));
                } else {
                    readings.add(new Reading(sum(s, p[i].spans(), fetched.get(s)), p[i].from(), p[i].to(), asOfMs,
                            p[i].exact(), Reading.Source.BUCKETS));
                }
            }
            out.put(s, readings);
        }
        return out;
    }

    private boolean viaRing(Window w, long asOfMs, long now) {
        return ringSnapshots && w instanceof Window.Rolling r
                && r.grain().floor(asOfMs, zone) == r.grain().floor(now, zone);
    }

    private Map<Labels, Map<BucketKey, Parts>> fetch(Map<Labels, Plan[]> plans) {
        Set<BucketKey> keys = new LinkedHashSet<>();
        List<Labels> series = new ArrayList<>();
        for (Map.Entry<Labels, Plan[]> e : plans.entrySet()) {
            boolean any = false;
            for (Plan p : e.getValue()) {
                if (p != null) {
                    any = true;
                    for (Span span : p.spans()) {
                        enumerate(span, keys);
                    }
                }
            }
            if (any) {
                series.add(e.getKey());
            }
        }
        return series.isEmpty() || keys.isEmpty() ? Map.of() : store.readMany(series, keys);
    }

    private void enumerate(Span span, Set<BucketKey> keys) {
        if (span.grain() == Grain.ALL) {
            keys.add(new BucketKey(Grain.ALL, 0));
            return;
        }
        for (long s = span.fromStart(); s < span.toStart(); s = span.grain().plus(s, 1, zone)) {
            keys.add(new BucketKey(span.grain(), s));
        }
    }

    private Parts sum(Labels series, List<Span> spans, Map<BucketKey, Parts> fetched) {
        Parts total = Parts.ZERO;
        for (Span span : spans) {
            if (store instanceof RangeStore range) {
                total = total.plus(span.grain() == Grain.ALL
                        ? range.sum(series, Grain.ALL, 0, 1)
                        : range.sum(series, span.grain(), span.fromStart(), span.toStart()));
            } else if (fetched != null) {
                Set<BucketKey> keys = new LinkedHashSet<>();
                enumerate(span, keys);
                for (BucketKey k : keys) {
                    Parts p = fetched.get(k);
                    if (p != null) {
                        total = total.plus(p);
                    }
                }
            }
        }
        return total;
    }

    private Parts sumRange(Labels series, Grain grain, long fromStart, long toStart) {
        if (store instanceof RangeStore range) {
            return range.sum(series, grain, fromStart, toStart);
        }
        Span span = new Span(grain, fromStart, toStart);
        Set<BucketKey> keys = new LinkedHashSet<>();
        enumerate(span, keys);
        if (keys.isEmpty()) {
            return Parts.ZERO;
        }
        return sum(series, List.of(span), store.readMany(List.of(series), keys).get(series));
    }

    private Plan plan(Labels series, Window window, long asOfMs, long now) {
        if (window instanceof Window.AllTime) {
            return new Plan(List.of(new Span(Grain.ALL, 0, 1)), Long.MIN_VALUE, asOfMs, true);
        }
        if (window instanceof Window.Calendar c) {
            Grain.Bucket b = Grain.parse(c.id(), zone);
            if (b.grain() == Grain.ALL) {
                return plan(series, Window.allTime(), asOfMs, now);
            }
            Tier tier = tier(b.grain());
            if (tier != null && b.start() >= earliest(tier, now)) {
                return new Plan(List.of(new Span(b.grain(), b.start(), b.end())), b.start(), b.end(), true);
            }
            return cover(b.start(), b.end(), now);
        }
        if (window instanceof Window.Rolling r) {
            long[] range = rollingRange(r.steps(), r.grain(), asOfMs, now);
            return new Plan(List.of(new Span(r.grain(), range[0], range[1])), range[0], range[1], true);
        }
        if (window instanceof Window.Range r) {
            return cover(r.fromMs(), r.toMs(), now);
        }
        if (window instanceof Window.Anchored a) {
            return anchored(series, a, asOfMs, now);
        }
        throw new IllegalStateException("Unknown window " + window);
    }

    /** {@code [from, to)} of a rolling window, checked against the tier. */
    private long[] rollingRange(int steps, Grain grain, long asOfMs, long now) {
        Tier tier = tier(grain);
        if (tier == null) {
            throw new IllegalArgumentException("No " + grain + " tier for a rolling window; tiers: " + tiers);
        }
        long current = grain.floor(asOfMs, zone);
        long from = grain.plus(current, -(steps - 1L), zone);
        if (from < earliest(tier, now)) {
            throw new IllegalArgumentException(steps + " " + grain + " steps reach past the " + grain
                    + " retention of " + tier.retention());
        }
        return new long[]{from, grain.end(current, zone)};
    }

    /** A rolling window through its stored aggregate. */
    private Reading ring(Labels series, int steps, Grain grain, long asOfMs, long now) {
        long[] range = rollingRange(steps, grain, asOfMs, now);
        long from = range[0];
        long to = range[1];
        long current = grain.floor(asOfMs, zone);
        Tier tier = tier(grain);
        String key = RingSnapshot.key(grain, steps);
        for (int attempt = 0; attempt < RING_ATTEMPTS; attempt++) {
            long version = store.version(series);
            RingSnapshot s = store.snapshot(series, key);
            if (s != null && s.currentStart() == current) {
                listener.onRing(RingOutcome.KEEP);
                return new Reading(s.sum(), from, to, asOfMs, true, Reading.Source.SNAPSHOT);
            }
            Parts sum;
            RingOutcome outcome;
            Reading.Source source;
            if (s == null) {
                sum = sumRange(series, grain, from, to);
                outcome = RingOutcome.MISS;
                source = Reading.Source.RECOMPUTED;
            } else if (s.currentStart() < current && s.fromStart() >= earliest(tier, now)
                    && grain.stepsBetween(s.currentStart(), current, zone) <= Math.min(MAX_ADVANCE_STEPS, steps - 1)) {
                Parts leaving = sumRange(series, grain, s.fromStart(), from);
                Parts entering = sumRange(series, grain, grain.end(s.currentStart(), zone), to);
                sum = s.sum().minus(leaving).plus(entering);
                outcome = RingOutcome.ADVANCE;
                source = Reading.Source.ADVANCED;
            } else {
                sum = sumRange(series, grain, from, to);
                outcome = RingOutcome.RECOMPUTE;
                source = Reading.Source.RECOMPUTED;
            }
            if (store.version(series) != version) {
                continue; // a write came in while we read: the sum may mix before and after
            }
            boolean stored = store.putSnapshot(series, key, new RingSnapshot(grain, steps, from, current, sum, version));
            listener.onRing(stored ? outcome : RingOutcome.REJECTED);
            return new Reading(sum, from, to, asOfMs, true, source);
        }
        listener.onRing(RingOutcome.REJECTED);
        return new Reading(sumRange(series, grain, from, to), from, to, asOfMs, true, Reading.Source.BUCKETS);
    }

    /** {@code [from, to)} from the coarsest whole buckets that fit, finer tiers at the edges. */
    private Plan cover(long from, long to, long now) {
        List<Span> spans = new ArrayList<>();
        boolean exact = cover(from, to, tiers.size() - 1, now, spans);
        return new Plan(spans, from, to, exact);
    }

    private boolean cover(long from, long to, int coarsest, long now, List<Span> spans) {
        if (from >= to) {
            return true;
        }
        for (int i = coarsest; i >= 0; i--) {
            Tier tier = tiers.get(i);
            Grain g = tier.grain();
            long a = ceil(g, Math.max(from, earliest(tier, now)));
            long b = g.floor(to, zone);
            if (a < b) {
                spans.add(new Span(g, a, b));
                boolean left = cover(from, a, i - 1, now, spans);
                boolean right = cover(b, to, i - 1, now, spans);
                return left && right;
            }
        }
        return false;
    }

    private long ceil(Grain g, long t) {
        long f = g.floor(t, zone);
        return f == t ? t : g.end(f, zone);
    }

    private Plan anchored(Labels series, Window.Anchored a, long asOfMs, long now) {
        Labels anchorSeries = series.with(Labels.METRIC_NAME, a.anchorMetric());
        OptionalLong first = store.firstAdded(anchorSeries);
        if (first.isEmpty()) {
            return new Plan(List.of(), asOfMs, asOfMs, true);
        }
        Tier finest = tiers.get(0);
        Grain g = finest.grain();
        long anchor = g.floor(first.getAsLong(), zone);
        if (a.recomputeOnRetraction() && anchor >= earliest(finest, now)
                && sumRange(anchorSeries, g, anchor, g.end(anchor, zone)).net() <= 0) {
            Long moved = firstPositive(anchorSeries, g, anchor, g.end(g.floor(now, zone), zone));
            if (moved == null) {
                return new Plan(List.of(), asOfMs, asOfMs, true);
            }
            anchor = moved;
        }
        long end = Math.addExact(anchor, a.length().toMillis());
        Plan p = cover(anchor, end, now);
        return new Plan(p.spans(), anchor, end, p.exact());
    }

    private Long firstPositive(Labels series, Grain g, long fromStart, long toStart) {
        SortedMap<Long, Parts> buckets;
        if (store instanceof RangeStore range) {
            buckets = range.buckets(series, g, fromStart, toStart);
        } else {
            Set<BucketKey> keys = new LinkedHashSet<>();
            enumerate(new Span(g, fromStart, toStart), keys);
            buckets = new TreeMap<>();
            Map<BucketKey, Parts> got = store.readMany(List.of(series), keys).getOrDefault(series, Map.of());
            got.forEach((k, p) -> buckets.put(k.start(), p));
        }
        for (Map.Entry<Long, Parts> e : buckets.entrySet()) {
            if (e.getValue().net() > 0) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Stored series matching every matcher; {@code =} and {@code !=} values go through the label's transform. Without
     * an {@link IndexedStore} the matchers must name one series exactly: {@code =} only.
     *
     * @throws QueryException when more than {@code maxSeriesPerSelector} match, or the store cannot search
     */
    List<Labels> select(List<Matcher> matchers) {
        List<Matcher> stored = new ArrayList<>(matchers.size());
        for (Matcher m : matchers) {
            LabelTransform t = transforms.get(m.name());
            if (t != null && (m.type() == Matcher.Type.EQUAL || m.type() == Matcher.Type.NOT_EQUAL)
                    && !m.value().isEmpty()) {
                String v = t.apply(m.value());
                stored.add(Matcher.of(m.type(), m.name(), v == null ? "" : v));
            } else {
                stored.add(m);
            }
        }
        if (!(store instanceof IndexedStore indexed)) {
            Map<String, String> exact = new TreeMap<>();
            for (Matcher m : stored) {
                if (m.type() != Matcher.Type.EQUAL || exact.put(m.name(), m.value()) != null) {
                    throw new QueryException("This store has no label index: name every label of the series with = "
                            + "(got " + matchers + ")");
                }
            }
            return List.of(Labels.of(exact));
        }
        List<Labels> out = indexed.series(stored, maxSeriesPerSelector);
        if (out.size() > maxSeriesPerSelector) {
            throw new QueryException("More than " + maxSeriesPerSelector + " series match " + matchers
                    + "; narrow the selector");
        }
        return out;
    }

    /** The finest fixed tier whose step divides {@code rangeMs} and whose retention holds it, for range functions. */
    Tier tierFor(long rangeMs) {
        for (Tier t : tiers) {
            long step = t.grain().stepMs();
            if (step > 0 && rangeMs % step == 0 && rangeMs <= t.retention().toMillis()) {
                return t;
            }
        }
        throw new QueryException("No second, minute or hour tier divides " + rangeMs + " ms and keeps that long; tiers: "
                + tiers);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // queries
    // ---------------------------------------------------------------------------------------------------------------

    /** Parses {@code promql} once, to run it many times with {@link Query#execute}. */
    public Query prepare(String promql) {
        return Query.parse(this, promql);
    }

    /** Evaluates {@code promql} now. */
    public QueryResult query(String promql) {
        return query(promql, clock.millis());
    }

    /** Evaluates {@code promql} at {@code timeMs}. */
    public QueryResult query(String promql, long timeMs) {
        long t = System.nanoTime();
        QueryResult r = prepare(promql).execute(timeMs);
        listener.onRead(ReadKind.QUERY, System.nanoTime() - t);
        return r;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // maintenance
    // ---------------------------------------------------------------------------------------------------------------

    /** What {@link #maintain} did. */
    public record Maintenance(int removedSeries, int expiredClaims) {
    }

    /**
     * Drops buckets past each tier's retention, expired claims, and series idle past their {@link CardinalityRule}'s
     * TTL. Run it every minute or so. Does nothing for a store that is not a {@link MaintainableStore}.
     */
    public Maintenance maintain() {
        if (!(store instanceof MaintainableStore maintainable)) {
            return new Maintenance(0, 0);
        }
        long now = clock.millis();
        for (Tier t : tiers) {
            maintainable.expire(t.grain(), earliest(t, now));
        }
        int claims = maintainable.expireClaims(now);
        int removed = 0;
        for (CardinalityRule r : rules) {
            if (r.idleTtl() != null) {
                int n = maintainable.removeIdle(r.label(), now - r.idleTtl().toMillis());
                removed += n;
                if (n > 0 && r.maxValues() > 0 && store instanceof IndexedStore indexed) {
                    Set<String> seen = seenValues.get(r.label());
                    synchronized (seen) {
                        seen.retainAll(indexed.labelValues(r.label()));
                    }
                }
            }
        }
        return new Maintenance(removed, claims);
    }

    /**
     * Merges {@code source} into this store: max per part and offset, earliest anchor, union of claims — to rebuild a
     * hot store from a cold one. Exact only if each copy applied every event at most once (see {@link Parts#max}).
     *
     * @throws IllegalStateException when this store cannot merge
     */
    public void rebuildFrom(ExportableStore source) {
        if (!(store instanceof ExportableStore target)) {
            throw new IllegalStateException("This store cannot merge: it is not an ExportableStore");
        }
        source.export(target.mergeMaxSink());
        if (store instanceof IndexedStore indexed) {
            for (CardinalityRule r : rules) {
                if (r.maxValues() > 0) {
                    Set<String> seen = seenValues.get(r.label());
                    synchronized (seen) {
                        seen.addAll(indexed.labelValues(r.label()));
                    }
                }
            }
        }
    }

    private Tier tier(Grain g) {
        for (Tier t : tiers) {
            if (t.grain() == g) {
                return t;
            }
        }
        return null;
    }

    private long earliest(Tier t, long now) {
        return t.grain().floor(now - t.retention().toMillis(), zone);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // builder
    // ---------------------------------------------------------------------------------------------------------------

    public static final class Builder {
        private final List<Tier> tiers = new ArrayList<>();
        private ZoneId zone = ZoneOffset.UTC;
        private Clock clock = Clock.systemUTC();
        private CounterStore store;
        private final Map<String, LabelTransform> transforms = new HashMap<>();
        private final List<CardinalityRule> rules = new ArrayList<>();
        private TsdbListener listener = TsdbListener.NONE;
        private int maxSeriesPerSelector = 100_000;
        private Duration claimTtl = Duration.ofDays(7);
        private boolean ringSnapshots;

        private Builder() {
        }

        /**
         * Keeps {@code grain} buckets for {@code retention}. Default when none is given: minutes for 48 hours, hours
         * for 62 days, days for 3 years.
         */
        public Builder tier(Grain grain, Duration retention) {
            tiers.add(new Tier(grain, retention));
            return this;
        }

        /** The zone of day, week, month and year buckets. Default UTC. */
        public Builder zone(ZoneId zone) {
            this.zone = zone;
            return this;
        }

        /** Default the system UTC clock. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Default a new {@link MemoryCounterStore}. */
        public Builder store(CounterStore store) {
            this.store = store;
            return this;
        }

        public Builder transform(String label, LabelTransform transform) {
            Labels.checkName(label);
            transforms.put(label, transform);
            return this;
        }

        /**
         * A cap or idle TTL on a label. The cap counts the values this process has seen (and, with an
         * {@link IndexedStore}, the values in the store when it starts).
         */
        public Builder rule(CardinalityRule rule) {
            rules.add(rule);
            return this;
        }

        public Builder listener(TsdbListener listener) {
            this.listener = listener;
            return this;
        }

        /** A selector matching more series fails instead of running. Default 100,000. */
        public Builder maxSeriesPerSelector(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxSeriesPerSelector must be positive");
            }
            this.maxSeriesPerSelector = max;
            return this;
        }

        /** How long a claim of {@link #applyOnce} lasts; null keeps claims forever. Default 7 days. */
        public Builder claimTtl(Duration ttl) {
            this.claimTtl = ttl;
            return this;
        }

        /**
         * Store each rolling window's aggregate and move it forward instead of summing its buckets on every read.
         * Worth it with a remote store; the memory store sums sparse buckets fast enough. Default off.
         */
        public Builder ringSnapshots(boolean on) {
            this.ringSnapshots = on;
            return this;
        }

        public TinyTsdb build() {
            if (tiers.isEmpty()) {
                tier(Grain.MINUTE, Duration.ofHours(48));
                tier(Grain.HOUR, Duration.ofDays(62));
                tier(Grain.DAY, Duration.ofDays(3 * 365));
            }
            Set<Grain> grains = EnumSet.noneOf(Grain.class);
            for (Tier t : tiers) {
                if (!grains.add(t.grain())) {
                    throw new IllegalArgumentException("Grain " + t.grain() + " is configured twice");
                }
            }
            tiers.sort(Comparator.comparing(Tier::grain));
            if (store == null) {
                store = new MemoryCounterStore();
            }
            return new TinyTsdb(this);
        }
    }
}
