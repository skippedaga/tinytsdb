package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The group operations: series of one label value, exported, removed only if unchanged, restored by adding. */
class MemoryCounterStoreGroupTest {

    static final long T = TinyTsdbTest.T;
    static final long MIN = 60_000;
    static final long DAY = 86_400_000;
    static final Set<Grain> WINDOWS = EnumSet.of(Grain.MINUTE, Grain.DAY);

    private final MemoryCounterStore store = new MemoryCounterStore();

    static Labels series(String user, String status) {
        return Labels.of(Labels.METRIC_NAME, "tx_total", "user", user, "status", status);
    }

    private void write(Labels s, long at, long delta) {
        store.apply(new Batch(List.of(new SeriesBatch(s, List.of(
                new BucketWrite(Grain.MINUTE, at - at % MIN, Parts.of(delta)),
                new BucketWrite(Grain.DAY, at - at % DAY, Parts.of(delta)),
                new BucketWrite(Grain.ALL, 0, Parts.of(delta))), delta > 0 ? at : Long.MAX_VALUE, at, null)), null, 0));
    }

    @Test
    void seriesOfFindsAGroupThroughTheIndex() {
        write(series("1", "SUCCESS"), T, 1);
        write(series("1", "FAILED"), T, 1);
        write(series("2", "SUCCESS"), T, 1);
        assertThat(store.seriesOf("user", "1")).containsExactlyInAnyOrder(series("1", "SUCCESS"), series("1", "FAILED"));
        assertThat(store.seriesOf("user", "3")).isEmpty();
        assertThat(store.seriesOf("nope", "1")).isEmpty();
    }

    @Test
    void removeDropsTheSeriesAndTheirIndexEntries() {
        write(series("1", "SUCCESS"), T, 1);
        write(series("2", "SUCCESS"), T, 1);
        assertThat(store.remove(List.of(series("1", "SUCCESS"), series("9", "SUCCESS")))).isEqualTo(1);
        assertThat(store.seriesCount()).isEqualTo(1);
        assertThat(store.labelValues("user")).containsExactly("2");
        assertThat(store.sum(series("1", "SUCCESS"), Grain.ALL, 0, 1)).isEqualTo(Parts.ZERO);
        // written again: a new series from zero
        write(series("1", "SUCCESS"), T, 5);
        assertThat(store.sum(series("1", "SUCCESS"), Grain.ALL, 0, 1)).isEqualTo(Parts.of(5));
    }

    @Test
    void anExportHoldsTheGrainsAskedForAndTheVersions() {
        write(series("1", "SUCCESS"), T, 2);
        write(series("1", "SUCCESS"), T + DAY, 3);
        write(series("1", "PENDING"), T, 1);
        write(series("1", "PENDING"), T + MIN, -1);
        SeriesGroup g = store.exportGroup("user", "1", WINDOWS);
        assertThat(g.series()).hasSize(2);
        SeriesGroup.Member success = g.series().stream().filter(m -> m.labels().equals(series("1", "SUCCESS")))
                .findFirst().orElseThrow();
        assertThat(success.version()).isEqualTo(2);
        assertThat(success.firstAddedMs()).isEqualTo(T);
        assertThat(success.buckets()).noneMatch(b -> b.grain() == Grain.ALL);
        assertThat(success.buckets()).hasSize(4); // two minutes, two days
        assertThat(store.exportGroup("user", "1", EnumSet.of(Grain.ALL)).series())
                .allSatisfy(m -> assertThat(m.buckets()).allMatch(b -> b.grain() == Grain.ALL));
        assertThat(store.exportGroup("user", "7", WINDOWS).series()).isEmpty();
        assertThat(store.exportGroup("user", "7", WINDOWS).isEmpty()).isTrue();
    }

    @Test
    void aGroupIsRemovedOnlyIfNothingChangedSinceItsExport() {
        write(series("1", "SUCCESS"), T, 1);
        write(series("1", "FAILED"), T, 1);

        SeriesGroup written = store.exportGroup("user", "1", WINDOWS);
        write(series("1", "SUCCESS"), T + MIN, 1); // a write since: its version moved
        assertThat(store.removeGroup(written)).isFalse();

        SeriesGroup added = store.exportGroup("user", "1", WINDOWS);
        write(series("1", "EXPIRED"), T + MIN, 1); // a new series of the group
        assertThat(store.removeGroup(added)).isFalse();

        SeriesGroup shrunk = store.exportGroup("user", "1", WINDOWS);
        store.remove(List.of(series("1", "EXPIRED"))); // one gone: the export no longer is the group
        assertThat(store.removeGroup(shrunk)).isFalse();
        assertThat(store.seriesOf("user", "1")).hasSize(2);

        write(series("2", "SUCCESS"), T, 1);
        SeriesGroup now = store.exportGroup("user", "1", WINDOWS);
        assertThat(store.removeGroup(now)).isTrue();
        assertThat(store.seriesOf("user", "1")).isEmpty();
        assertThat(store.labelValues("user")).containsExactly("2");
        assertThat(store.labelValues("status")).containsExactly("SUCCESS");
        // removed already: the export is not the group any more
        assertThat(store.removeGroup(now)).isFalse();
    }

    @Test
    void anEmptyGroupIsRemovedAndAnUnknownOneIsNot() {
        assertThat(store.removeGroup(new SeriesGroup("user", "1", List.of()))).isTrue(); // nothing held, nothing to do
        write(series("1", "SUCCESS"), T, 1);
        assertThat(store.removeGroup(new SeriesGroup("user", "1", List.of()))).isFalse();
    }

    @Test
    void aRestoreAddsToWhatWasWrittenMeanwhile() {
        write(series("1", "SUCCESS"), T, 2);
        write(series("1", "FAILED"), T + DAY, 1);
        SeriesGroup g = store.exportGroup("user", "1", WINDOWS);
        assertThat(store.removeGroup(g)).isTrue();
        write(series("1", "SUCCESS"), T, 3); // a write while the group was out: counted at once

        assertThat(store.restore(g.series(), T + 2 * DAY)).isEqualTo(2);
        assertThat(store.sum(series("1", "SUCCESS"), Grain.MINUTE, T, T + MIN)).isEqualTo(Parts.of(5));
        assertThat(store.sum(series("1", "SUCCESS"), Grain.DAY, T - DAY, T + DAY)).isEqualTo(Parts.of(5));
        assertThat(store.sum(series("1", "FAILED"), Grain.DAY, T, T + 2 * DAY)).isEqualTo(Parts.of(1));
        assertThat(store.firstAdded(series("1", "FAILED"))).hasValue(T + DAY);
        // the grains not exported are not brought back: all time only has the write made meanwhile
        assertThat(store.sum(series("1", "SUCCESS"), Grain.ALL, 0, 1)).isEqualTo(Parts.of(3));
        assertThat(store.sum(series("1", "FAILED"), Grain.ALL, 0, 1)).isEqualTo(Parts.ZERO);
    }

    @Test
    void retractionsTravelWithTheGroup() {
        write(series("1", "PENDING"), T, 1);
        write(series("1", "PENDING"), T + MIN, -1);
        SeriesGroup g = store.exportGroup("user", "1", WINDOWS);
        assertThat(store.removeGroup(g)).isTrue();
        store.restore(g.series(), T);
        assertThat(store.sum(series("1", "PENDING"), Grain.MINUTE, T, T + 2 * MIN)).isEqualTo(new Parts(1, 1));
    }

    @Test
    void aSnapshotAfterARemovalHoldsOnlyWhatIsLeft() throws IOException {
        write(series("1", "SUCCESS"), T, 1);
        write(series("2", "SUCCESS"), T, 1);
        store.removeGroup(store.exportGroup("user", "1", WINDOWS));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.writeSnapshot(out);
        MemoryCounterStore back = MemoryCounterStore.readSnapshot(new ByteArrayInputStream(out.toByteArray()));
        assertThat(back.seriesCount()).isEqualTo(1);
        assertThat(back.labelValues("user")).containsExactly("2");
    }
}
