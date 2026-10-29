package io.github.skippedaga.tinytsdb;

/**
 * A PN counter value: two parts that only grow. A positive delta adds to {@code added}, a negative one to
 * {@code retracted}; the value is {@link #net()}. Integers only: amounts go in minor units.
 *
 * <p>{@link #max} merges two copies part by part. That is exact only when every copy applied each event at most
 * once, so that one copy's events are a subset of the other's — a rebuild from a cold store, a replica that fell
 * behind. Two copies that applied <em>different</em> events cannot be merged this way.</p>
 */
public record Parts(long added, long retracted) {

    public static final Parts ZERO = new Parts(0, 0);

    public Parts {
        if (added < 0 || retracted < 0) {
            throw new IllegalArgumentException("Both parts only grow, got " + added + " / " + retracted);
        }
    }

    /** The parts of one delta: positive → added, negative → retracted. */
    public static Parts of(long delta) {
        if (delta == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Delta out of range");
        }
        return delta >= 0 ? new Parts(delta, 0) : new Parts(0, -delta);
    }

    /** {@code added − retracted}. */
    public long net() {
        return Math.subtractExact(added, retracted);
    }

    public Parts plus(Parts other) {
        return new Parts(Math.addExact(added, other.added), Math.addExact(retracted, other.retracted));
    }

    /** Removes what {@code other} added to these parts; used when a bucket leaves a window. */
    public Parts minus(Parts other) {
        return new Parts(added - other.added, retracted - other.retracted);
    }

    public Parts max(Parts other) {
        return new Parts(Math.max(added, other.added), Math.max(retracted, other.retracted));
    }

    public boolean isZero() {
        return added == 0 && retracted == 0;
    }
}
