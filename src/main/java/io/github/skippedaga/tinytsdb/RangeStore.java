package io.github.skippedaga.tinytsdb;

import java.util.SortedMap;

/**
 * A store that can sum and list a range of buckets itself. Without it, TinyTsdb lists the bucket starts of the
 * range and reads them with {@link CounterStore#readMany}.
 */
public interface RangeStore extends CounterStore {

    /** The sum of the buckets starting in {@code [fromStart, toStart)}. */
    Parts sum(Labels series, Grain grain, long fromStart, long toStart);

    /** The non-empty buckets starting in {@code [fromStart, toStart)}, by start. */
    SortedMap<Long, Parts> buckets(Labels series, Grain grain, long fromStart, long toStart);
}
