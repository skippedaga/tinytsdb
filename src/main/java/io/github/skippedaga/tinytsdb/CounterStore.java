package io.github.skippedaga.tinytsdb;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;

/**
 * Where buckets live. {@link MemoryCounterStore} ships with the library; a product can bring its own (Redis for the hot
 * buckets, a database for history) and keep the same reads and queries.
 *
 * <p>A series is identified by its {@link Labels}, metric name included. Every series has a bucket per configured
 * grain plus one {@link Grain#ALL} bucket (start 0) for all time. Bucket starts come from {@link Grain#floor}; a store
 * never needs to do time arithmetic itself.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #apply} is atomic: all of a batch or none of it, and with a claim key, at most once per key.</li>
 *   <li>Every write to a series raises its {@link #version}.</li>
 *   <li>A write also patches the series' stored {@link RingSnapshot}s with {@link RingSnapshot#patch}, in the same
 *       atomic step, and gives them the new version.</li>
 *   <li>{@link #putSnapshot} stores a snapshot only if its version is the series' current one, so a read computed
 *       before a concurrent write cannot store a stale aggregate.</li>
 * </ul>
 */
public interface CounterStore {

    /** One bucket delta. */
    record BucketWrite(Labels series, Grain grain, long start, Parts delta) {
    }

    /**
     * What a write tells the store about its series besides the buckets.
     *
     * @param occurredAtMs the event time, for the first-added anchor (minimum over writes with {@code added > 0})
     * @param positive     the delta added something
     * @param writtenAtMs  wall-clock time of the write, for idle expiry
     */
    record SeriesWrite(Labels series, long occurredAtMs, boolean positive, long writtenAtMs) {
    }

    /**
     * One atomic write.
     *
     * @param claimKey         null for no idempotency; otherwise the batch applies only if the key is unclaimed
     * @param claimExpiresAtMs when the claim may be forgotten ({@code Long.MAX_VALUE}: never)
     */
    record Batch(List<BucketWrite> buckets, List<SeriesWrite> series, String claimKey, long claimExpiresAtMs) {

        public Batch {
            buckets = List.copyOf(buckets);
            series = List.copyOf(series);
        }
    }

    /** @return false when the batch carried a claim key that was already claimed: nothing was written */
    boolean apply(Batch batch);

    /** Forgets a claim, e.g. after the caller's own processing failed, so a retry is not taken for a repeat. */
    void releaseClaim(String claimKey);

    /** One bucket; {@link Parts#ZERO} when empty or unknown. */
    Parts read(Labels series, Grain grain, long start);

    /** The sum of the buckets starting in {@code [fromStart, toStart)}. */
    Parts sum(Labels series, Grain grain, long fromStart, long toStart);

    /** The non-empty buckets starting in {@code [fromStart, toStart)}, by start. */
    SortedMap<Long, Parts> buckets(Labels series, Grain grain, long fromStart, long toStart);

    /** The earliest event time with a positive delta, if any. */
    OptionalLong firstAdded(Labels series);

    /** Raised by every write to the series; 0 for an unknown series. */
    long version(Labels series);

    /**
     * The series matching every matcher (a missing label matches as {@code ""}).
     *
     * @return at most {@code limit + 1} series, so the caller can tell the limit was crossed
     */
    List<Labels> series(List<Matcher> matchers, int limit);

    /** The values of {@code label} over all series. */
    Set<String> labelValues(String label);

    /** A stored rolling-window aggregate, or null. */
    RingSnapshot snapshot(Labels series, String key);

    /** @return false when refused: the series has changed since {@code snapshot.version()} */
    boolean putSnapshot(Labels series, String key, RingSnapshot snapshot);

    /** Drops the {@code grain} buckets that start before {@code beforeStart}, and snapshots of that grain. */
    void expire(Grain grain, long beforeStart);

    /** Drops claims that expired by {@code nowMs}; returns how many. */
    int expireClaims(long nowMs);

    /**
     * Removes entirely the series that have {@code label} and no write since {@code lastWriteBeforeMs}.
     *
     * @return how many series were removed
     */
    int removeIdle(String label, long lastWriteBeforeMs);

    /** Hands every bucket, anchor and claim to {@code sink}: for a rebuild, a backup, a move to another store. */
    void export(StoreSink sink);

    /** A sink that merges what it is given into this store: max per part, min per anchor, union of claims. */
    StoreSink mergeMaxSink();
}
