package io.github.skippedaga.tinytsdb;

/**
 * Hooks for the caller's metrics. Every method has a no-op default; implementations must be fast and must not throw.
 * Label values never reach a hook.
 */
public interface TsdbListener {

    /** What a read of a rolling window did with its stored aggregate. */
    enum RingOutcome {
        /** No aggregate stored: computed from the buckets. */
        MISS,
        /** Stored aggregate up to date. */
        KEEP,
        /** Moved forward by a few steps. */
        ADVANCE,
        /** Too far behind, or read for a past time: computed from the buckets. */
        RECOMPUTE,
        /** Computed, but a concurrent write changed the series: not stored. */
        REJECTED
    }

    enum ReadKind {
        CALENDAR, ROLLING, RANGE, ANCHORED, ALL_TIME, QUERY
    }

    default void onWrite(int increments, long nanos) {
    }

    default void onDuplicate() {
    }

    default void onRead(ReadKind kind, long nanos) {
    }

    default void onRing(RingOutcome outcome) {
    }

    /** A new value of the label was written as the overflow value. */
    default void onOverflow(String label) {
    }

    TsdbListener NONE = new TsdbListener() {
    };
}
