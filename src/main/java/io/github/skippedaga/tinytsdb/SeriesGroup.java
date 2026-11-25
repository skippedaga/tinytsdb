package io.github.skippedaga.tinytsdb;

import java.util.List;

/**
 * The series that carry one value of one label (a group, such as one user's series) as {@link MemoryCounterStore}
 * exported them: per series its labels, first-added time, version and buckets of the grains asked for. Stream
 * positions and ring snapshots are not part of it.
 *
 * <p>{@link MemoryCounterStore#removeGroup} removes the group only if it is still exactly this export (no series
 * written since, none added), and {@link MemoryCounterStore#restore} adds the buckets back.</p>
 *
 * @param label  the label, such as {@code user}
 * @param value  its value, such as {@code 42}
 * @param series the group's series, every one carrying {@code label=value}
 */
public record SeriesGroup(String label, String value, List<Member> series) {

    public SeriesGroup {
        series = List.copyOf(series);
    }

    /**
     * One series of the group.
     *
     * @param labels       every label, the group's included
     * @param firstAddedMs when it was first added to, {@link Long#MAX_VALUE} for never
     * @param version      its version when exported (a write raises it)
     * @param buckets      its non-empty buckets of the grains exported
     */
    public record Member(Labels labels, long firstAddedMs, long version, List<CounterStore.BucketWrite> buckets) {

        public Member {
            buckets = List.copyOf(buckets);
        }
    }

    /** No bucket in any series (a group may have series and nothing in the grains exported). */
    public boolean isEmpty() {
        for (Member m : series) {
            if (!m.buckets().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
