package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesWrite;
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
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;

/**
 * Counters with labels, kept per time bucket at the grains you configure, read over calendar, rolling, anchored and
 * arbitrary windows, and queried with a subset of PromQL.
 *
 * <ul>
 *   <li>Values are PN counters of 64-bit integers ({@link Parts}): a negative delta is a retraction, kept apart.</li>
 *   <li>Every write goes to the bucket of the event's own time, at every grain whose retention still covers it — a
 *       correction months later lands where the event was.</li>
 *   <li>{@link #applyOnce} writes a batch at most once per caller-supplied key.</li>
 *   <li>Storage is a {@link CounterStore}: {@link MemoryCounterStore} by default.</li>
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
                seenValues.put(r.label(), new HashSet<>(store.labelValues(r.label())));
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

    /** Writes the increments as one atomic batch. */
    public void write(List<Increment> increments) {
        long t = System.nanoTime();
        store.apply(batch(increments, null, 0));
        listener.onWrite(increments.size(), System.nanoTime() - t);
    }

    /**
     * Writes the increments as one atomic batch unless {@code claimKey} was claimed before; the claim lasts
     * {@code claimTtl}. The key is the caller's and must name the change, e.g. event id plus version. Keep the TTL at
     * least as long as the source log's retention, or a replay can count again.
     *
     * @return false for a repeat: nothing was written
     */
    public boolean applyOnce(String claimKey, List<Increment> increments) {
        if (claimKey == null || claimKey.isEmpty()) {
            throw new IllegalArgumentException("claimKey must not be empty");
        }
        long t = System.nanoTime();
        long expires = claimTtl == null ? Long.MAX_VALUE : clock.millis() + claimTtl.toMillis();
        boolean applied = store.apply(batch(increments, claimKey, expires));
        if (applied) {
            listener.onWrite(increments.size(), System.nanoTime() - t);
        } else {
            listener.onDuplicate();
        }
        return applied;
    }

    /** Forgets a claim, so a retry after the caller's own failure is applied. */
    public void releaseClaim(String claimKey) {
        store.releaseClaim(claimKey);
    }

    private Batch batch(List<Increment> increments, String claimKey, long claimExpiresAt) {
        long now = clock.millis();
        List<BucketWrite> buckets = new ArrayList<>(increments.size() * (tiers.size() + 1));
        List<SeriesWrite> series = new ArrayList<>(increments.size());
        for (Increment inc : increments) {
            if (inc.delta() == 0) {
                continue;
            }
            Labels s = guard(transform(inc.labels())).with(Labels.METRIC_NAME, inc.metric());
            Parts delta = Parts.of(inc.delta());
            for (Tier tier : tiers) {
                long start = tier.grain().floor(inc.occurredAtMs(), zone);
                if (start >= earliest(tier, now)) {
                    buckets.add(new BucketWrite(s, tier.grain(), start, delta));
                }
            }
            buckets.add(new BucketWrite(s, Grain.ALL, 0, delta));
            series.add(new SeriesWrite(s, inc.occurredAtMs(), delta.added() > 0, now));
        }
        return new Batch(buckets, series, claimKey, claimExpiresAt);
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
        Labels.checkMetricName(metric);
        long t = System.nanoTime();
        Reading r = readSeries(transform(labels).with(Labels.METRIC_NAME, metric), window, asOfMs);
        listener.onRead(kind(window), System.nanoTime() - t);
        return r;
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
        Labels.checkMetricName(metric);
        long t = System.nanoTime();
        List<Matcher> all = new ArrayList<>(matchers);
        all.add(Matcher.of(Matcher.Type.EQUAL, Labels.METRIC_NAME, metric));
        Map<Labels, Reading> out = new LinkedHashMap<>();
        for (Labels s : select(all)) {
            Reading r = readSeries(s, window, asOfMs);
            out.merge(s.keep(groupBy), r, Reading::plus);
        }
        listener.onRead(kind(window), System.nanoTime() - t);
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

    /** {@code series} is stored labels: transformed, metric name included. */
    Reading readSeries(Labels series, Window window, long asOfMs) {
        if (window instanceof Window.AllTime) {
            return new Reading(store.read(series, Grain.ALL, 0), Long.MIN_VALUE, asOfMs, asOfMs, true);
        }
        if (window instanceof Window.Calendar c) {
            Grain.Bucket b = Grain.parse(c.id(), zone);
            if (b.grain() == Grain.ALL) {
                return readSeries(series, Window.allTime(), asOfMs);
            }
            Tier tier = tier(b.grain());
            if (tier != null && b.start() >= earliest(tier, clock.millis())) {
                return new Reading(store.read(series, b.grain(), b.start()), b.start(), b.end(), asOfMs, true);
            }
            return range(series, b.start(), b.end(), asOfMs);
        }
        if (window instanceof Window.Rolling r) {
            return rolling(series, r.steps(), r.grain(), asOfMs);
        }
        if (window instanceof Window.Range r) {
            return range(series, r.fromMs(), r.toMs(), asOfMs);
        }
        if (window instanceof Window.Anchored a) {
            return anchored(series, a, asOfMs);
        }
        throw new IllegalStateException("Unknown window " + window);
    }

    Reading rolling(Labels series, int steps, Grain grain, long asOfMs) {
        Tier tier = tier(grain);
        if (tier == null) {
            throw new IllegalArgumentException("No " + grain + " tier for a rolling window; tiers: " + tiers);
        }
        long now = clock.millis();
        long current = grain.floor(asOfMs, zone);
        long from = grain.plus(current, -(steps - 1L), zone);
        long to = grain.end(current, zone);
        if (from < earliest(tier, now)) {
            throw new IllegalArgumentException(steps + " " + grain + " steps reach past the " + grain
                    + " retention of " + tier.retention());
        }
        if (!ringSnapshots || current != grain.floor(now, zone)) {
            // no aggregate kept, or a read for another step than the current one: sum the buckets
            return new Reading(store.sum(series, grain, from, to), from, to, asOfMs, true);
        }
        String key = RingSnapshot.key(grain, steps);
        for (int attempt = 0; attempt < RING_ATTEMPTS; attempt++) {
            long version = store.version(series);
            RingSnapshot s = store.snapshot(series, key);
            if (s != null && s.currentStart() == current) {
                listener.onRing(RingOutcome.KEEP);
                return new Reading(s.sum(), from, to, asOfMs, true);
            }
            Parts sum;
            RingOutcome outcome;
            if (s == null) {
                sum = store.sum(series, grain, from, to);
                outcome = RingOutcome.MISS;
            } else if (s.currentStart() < current && s.fromStart() >= earliest(tier, now)
                    && grain.stepsBetween(s.currentStart(), current, zone) <= Math.min(MAX_ADVANCE_STEPS, steps - 1)) {
                Parts leaving = store.sum(series, grain, s.fromStart(), from);
                Parts entering = store.sum(series, grain, grain.end(s.currentStart(), zone), to);
                sum = s.sum().minus(leaving).plus(entering);
                outcome = RingOutcome.ADVANCE;
            } else {
                sum = store.sum(series, grain, from, to);
                outcome = RingOutcome.RECOMPUTE;
            }
            if (store.version(series) != version) {
                continue; // a write came in while we read: the sum may mix before and after
            }
            boolean stored = store.putSnapshot(series, key, new RingSnapshot(grain, steps, from, current, sum, version));
            listener.onRing(stored ? outcome : RingOutcome.REJECTED);
            return new Reading(sum, from, to, asOfMs, true);
        }
        listener.onRing(RingOutcome.REJECTED);
        return new Reading(store.sum(series, grain, from, to), from, to, asOfMs, true);
    }

    Reading range(Labels series, long from, long to, long asOfMs) {
        Cover c = cover(series, from, to, tiers.size() - 1, clock.millis());
        return new Reading(c.parts, from, to, asOfMs, c.exact);
    }

    private record Cover(Parts parts, boolean exact) {
    }

    /** {@code [from, to)} from the coarsest whole buckets that fit, recursing into finer tiers at the edges. */
    private Cover cover(Labels series, long from, long to, int coarsest, long now) {
        if (from >= to) {
            return new Cover(Parts.ZERO, true);
        }
        for (int i = coarsest; i >= 0; i--) {
            Tier tier = tiers.get(i);
            Grain g = tier.grain();
            long a = ceil(g, Math.max(from, earliest(tier, now)));
            long b = g.floor(to, zone);
            if (a < b) {
                Parts mid = store.sum(series, g, a, b);
                Cover left = cover(series, from, a, i - 1, now);
                Cover right = cover(series, b, to, i - 1, now);
                return new Cover(mid.plus(left.parts).plus(right.parts), left.exact && right.exact);
            }
        }
        return new Cover(Parts.ZERO, false);
    }

    private long ceil(Grain g, long t) {
        long f = g.floor(t, zone);
        return f == t ? t : g.end(f, zone);
    }

    private Reading anchored(Labels series, Window.Anchored a, long asOfMs) {
        Labels anchorSeries = series.with(Labels.METRIC_NAME, a.anchorMetric());
        OptionalLong first = store.firstAdded(anchorSeries);
        if (first.isEmpty()) {
            return new Reading(Parts.ZERO, asOfMs, asOfMs, asOfMs, true);
        }
        Tier finest = tiers.get(0);
        Grain g = finest.grain();
        long anchor = g.floor(first.getAsLong(), zone);
        if (a.recomputeOnRetraction() && anchor >= earliest(finest, clock.millis())
                && store.read(anchorSeries, g, anchor).net() <= 0) {
            SortedMap<Long, Parts> later = store.buckets(anchorSeries, g, anchor, Long.MAX_VALUE);
            Long moved = null;
            for (Map.Entry<Long, Parts> e : later.entrySet()) {
                if (e.getValue().net() > 0) {
                    moved = e.getKey();
                    break;
                }
            }
            if (moved == null) {
                return new Reading(Parts.ZERO, asOfMs, asOfMs, asOfMs, true);
            }
            anchor = moved;
        }
        long end = Math.addExact(anchor, a.length().toMillis());
        Reading r = range(series, anchor, end, asOfMs);
        return new Reading(r.parts(), anchor, end, asOfMs, r.exact());
    }

    /**
     * Stored series matching every matcher; {@code =} and {@code !=} values go through the label's transform.
     *
     * @throws QueryException when more than {@code maxSeriesPerSelector} match
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
        List<Labels> out = store.series(stored, maxSeriesPerSelector);
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
     * TTL. Run it every minute or so.
     */
    public Maintenance maintain() {
        long now = clock.millis();
        for (Tier t : tiers) {
            store.expire(t.grain(), earliest(t, now));
        }
        int claims = store.expireClaims(now);
        int removed = 0;
        for (CardinalityRule r : rules) {
            if (r.idleTtl() != null) {
                int n = store.removeIdle(r.label(), now - r.idleTtl().toMillis());
                removed += n;
                if (n > 0 && r.maxValues() > 0) {
                    Set<String> seen = seenValues.get(r.label());
                    synchronized (seen) {
                        seen.retainAll(store.labelValues(r.label()));
                    }
                }
            }
        }
        return new Maintenance(removed, claims);
    }

    /**
     * Merges {@code source} into this store: max per part, earliest anchor, union of claims — to rebuild a hot store
     * from a cold one. Exact only if each copy applied every event at most once (see {@link Parts#max}).
     */
    public void rebuildFrom(CounterStore source) {
        source.export(store.mergeMaxSink());
        for (CardinalityRule r : rules) {
            if (r.maxValues() > 0) {
                Set<String> seen = seenValues.get(r.label());
                synchronized (seen) {
                    seen.addAll(store.labelValues(r.label()));
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
