package io.github.skippedaga.tinytsdb;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Where buckets live. This is the core every store implements: atomic writes, bulk reads, the first-added anchor,
 * the series version, and stored rolling aggregates. A store can add capabilities:
 * {@link RangeStore} (sums over a range without listing buckets), {@link IndexedStore} (series by label matchers),
 * {@link MaintainableStore} (expiry run by {@link TinyTsdb#maintain}) and {@link ExportableStore} (export and
 * merge). {@link MemoryCounterStore} has them all; a Redis store might have none and still serve every read by
 * series.
 *
 * <p>A series is identified by its {@link Labels}, metric name included. It has a bucket per configured grain plus
 * one {@link Grain#ALL} bucket (start 0) for all time. Bucket starts come from {@link Grain#floor}: a store does no
 * time arithmetic. How a store lays series out is its own business; it may keep several metrics of one key together
 * ({@link StorageKey}).</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #apply} is atomic per {@link #atomicity()}: the whole batch with its claim ({@link Atomicity#BATCH}),
 *       or each {@link SeriesBatch} on its own ({@link Atomicity#SERIES}).</li>
 *   <li>A {@link SeriesBatch} with a {@link Position} is applied only if its offset is past the one the series last
 *       applied for that stream, and records the new offset in the same atomic step. A retried batch therefore skips
 *       the series that already took it, whatever else changed in the batch.</li>
 *   <li>Every write to a series raises its {@link #version}, and patches the series' stored {@link RingSnapshot}s
 *       with {@link RingSnapshot#patch} in the same atomic step.</li>
 *   <li>{@link #putSnapshot} stores a snapshot only if its version is the series' current one.</li>
 * </ul>
 */
public interface CounterStore {

    /** What one {@link #apply} call guarantees. */
    enum Atomicity {
        /** The whole batch and its claim, or nothing. */
        BATCH,
        /**
         * Each {@link SeriesBatch} on its own (e.g. one key per series in a sharded store). TinyTsdb takes a claim
         * with {@link #claim} first and releases it if the writes fail.
         */
        SERIES
    }

    /** A bucket of a series. */
    record BucketKey(Grain grain, long start) {
    }

    /** One bucket delta of a series. */
    record BucketWrite(Grain grain, long start, Parts delta) {
    }

    /** Where in an ordered source (a Kafka partition, a log) a write comes from. Offsets only grow per stream. */
    record Position(String stream, long offset) {

        public Position {
            if (stream == null || stream.isEmpty()) {
                throw new IllegalArgumentException("stream must not be empty");
            }
        }
    }

    /**
     * Everything one batch writes to one series.
     *
     * @param firstAddedMs the earliest event time with a positive delta in this batch, {@code Long.MAX_VALUE} if none
     * @param writtenAtMs  wall-clock time of the write, for idle expiry
     * @param position     null, or where the batch comes from: applied only past the series' last offset for it
     */
    record SeriesBatch(Labels series, List<BucketWrite> buckets, long firstAddedMs, long writtenAtMs,
                       Position position) {

        public SeriesBatch {
            buckets = List.copyOf(buckets);
        }
    }

    /**
     * One write.
     *
     * @param claimKey         null for none; with {@link Atomicity#BATCH} the batch applies only if the key is
     *                         unclaimed, and claims it in the same step
     * @param claimExpiresAtMs when the claim may be forgotten ({@code Long.MAX_VALUE}: never)
     */
    record Batch(List<SeriesBatch> series, String claimKey, long claimExpiresAtMs) {

        public Batch {
            series = List.copyOf(series);
        }
    }

    /**
     * What {@link #apply} did.
     *
     * @param duplicate the claim key was claimed before: nothing was written
     * @param applied   series written
     * @param skipped   series whose position was not past their last offset: already applied
     */
    record Result(boolean duplicate, int applied, int skipped) {

        public static final Result DUPLICATE = new Result(true, 0, 0);
    }

    /** {@link Atomicity#BATCH} unless the store says otherwise. */
    default Atomicity atomicity() {
        return Atomicity.BATCH;
    }

    Result apply(Batch batch);

    /**
     * Claims {@code key} until {@code expiresAtMs} unless it is claimed. Used before {@link #apply} by stores of
     * {@link Atomicity#SERIES}; a {@link Atomicity#BATCH} store claims inside {@code apply}.
     *
     * @return false when the key was claimed before
     */
    boolean claim(String key, long expiresAtMs);

    /** Forgets a claim, so a retry after the caller's own failure is applied. */
    void releaseClaim(String key);

    /**
     * The given buckets of the given series, in as few round trips as the store can: one per series, or fewer when
     * series share a {@link StorageKey}. Empty and unknown buckets may be left out.
     */
    Map<Labels, Map<BucketKey, Parts>> readMany(Collection<Labels> series, Collection<BucketKey> keys);

    /** The earliest event time with a positive delta, if any. */
    OptionalLong firstAdded(Labels series);

    /** Raised by every write to the series; 0 for an unknown series. */
    long version(Labels series);

    /** A stored rolling-window aggregate, or null. */
    RingSnapshot snapshot(Labels series, String key);

    /** @return false when refused: the series changed since {@code snapshot.version()}, or a fresher one is stored */
    boolean putSnapshot(Labels series, String key, RingSnapshot snapshot);
}
