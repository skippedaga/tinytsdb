package io.github.skippedaga.tinytsdb;

/**
 * The stored aggregate of one rolling window of one series: the sum of the {@code grain} buckets starting in
 * {@code [fromStart, currentStart]}, i.e. the last {@code steps} buckets including the one in progress.
 *
 * <p>The buckets themselves stay in the store; the snapshot only saves summing them on every read. A read moves it
 * forward by subtracting the buckets that left and adding those that came in, or recomputes it.</p>
 *
 * @param version the series' {@link CounterStore#version} the aggregate is exact for
 */
public record RingSnapshot(Grain grain, int steps, long fromStart, long currentStart, Parts sum, long version) {

    /** Key of a rolling window in the store: {@code r:<grain>:<steps>}. */
    public static String key(Grain grain, int steps) {
        return "r:" + grain.prefix() + ':' + steps;
    }

    public String key() {
        return key(grain, steps);
    }

    /**
     * This snapshot after a write of {@code delta} into the bucket starting at {@code start}, at series version
     * {@code newVersion}. A bucket inside the window changes the sum; any other leaves it as it is — one after
     * {@code currentStart} is picked up when the window moves there.
     */
    public RingSnapshot patch(long start, Parts delta, long newVersion) {
        Parts s = start >= fromStart && start <= currentStart ? sum.plus(delta) : sum;
        return new RingSnapshot(grain, steps, fromStart, currentStart, s, newVersion);
    }
}
