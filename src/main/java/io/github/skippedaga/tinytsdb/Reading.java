package io.github.skippedaga.tinytsdb;

/**
 * The value of a series (or a group of series) over a window.
 *
 * @param fromMs start of what was counted (inclusive)
 * @param toMs   end of what was counted (exclusive)
 * @param asOfMs the moment the read was made for
 * @param exact  false when an edge of the requested window fell inside a bucket of the finest grain kept there, or
 *               beyond every retention: that part was left out (never counted twice)
 * @param source where the value came from: the buckets, or a stored rolling aggregate kept, moved forward or
 *               recomputed
 */
public record Reading(Parts parts, long fromMs, long toMs, long asOfMs, boolean exact, Source source) {

    /** Where a value came from. */
    public enum Source {
        /** Summed from the buckets. */
        BUCKETS,
        /** A stored rolling aggregate, up to date. */
        SNAPSHOT,
        /** A stored rolling aggregate moved forward a few steps. */
        ADVANCED,
        /** A rolling aggregate computed again from the buckets and stored. */
        RECOMPUTED
    }

    public long net() {
        return parts.net();
    }

    public long added() {
        return parts.added();
    }

    public long retracted() {
        return parts.retracted();
    }

    /** Both readings added; the range is the union, exact only if both are. */
    public Reading plus(Reading other) {
        return new Reading(parts.plus(other.parts), Math.min(fromMs, other.fromMs), Math.max(toMs, other.toMs),
                asOfMs, exact && other.exact, source == other.source ? source : Source.BUCKETS);
    }
}
