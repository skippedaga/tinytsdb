package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketKey;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.Position;
import io.github.skippedaga.tinytsdb.CounterStore.Result;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesBatch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryCounterStoreTest {

    static final long T = TinyTsdbTest.T;
    static final Labels S = Labels.of(Labels.METRIC_NAME, "x_total", "a", "1");

    private final MemoryCounterStore store = new MemoryCounterStore();

    private Result write(long start, long delta, Position position) {
        return store.apply(new Batch(List.of(new SeriesBatch(S,
                List.of(new BucketWrite(Grain.MINUTE, start, Parts.of(delta))),
                delta > 0 ? start : Long.MAX_VALUE, T, position)), null, 0));
    }

    private void write(long start, long delta) {
        write(start, delta, null);
    }

    @Test
    void bucketsSumAndRangeInAnyWriteOrder() {
        write(T + 120_000, 1);
        write(T, 2);
        write(T + 60_000, -3);
        write(T, 4);

        assertThat(store.sum(S, Grain.MINUTE, T, T + 60_000)).isEqualTo(new Parts(6, 0));
        assertThat(store.sum(S, Grain.MINUTE, T, T + 120_000)).isEqualTo(new Parts(6, 3));
        assertThat(store.buckets(S, Grain.MINUTE, T, Long.MAX_VALUE)).containsOnlyKeys(T, T + 60_000, T + 120_000);
        assertThat(store.readMany(List.of(S, Labels.of(Labels.METRIC_NAME, "unknown_total")),
                List.of(new BucketKey(Grain.MINUTE, T), new BucketKey(Grain.MINUTE, T + 600_000))))
                .containsOnlyKeys(S)
                .satisfies(m -> assertThat(m.get(S)).containsExactly(
                        java.util.Map.entry(new BucketKey(Grain.MINUTE, T), new Parts(6, 0))));
        assertThat(store.version(S)).isEqualTo(4);
        assertThat(store.firstAdded(S)).hasValue(T);
    }

    @Test
    void aPositionAppliesASeriesOncePerOffset() {
        assertThat(write(T, 1, new Position("p0", 10))).isEqualTo(new Result(false, 1, 0));
        assertThat(write(T, 1, new Position("p0", 10))).isEqualTo(new Result(false, 0, 1));
        assertThat(write(T, 1, new Position("p0", 9))).isEqualTo(new Result(false, 0, 1));
        assertThat(write(T, 1, new Position("p1", 3))).as("another stream").isEqualTo(new Result(false, 1, 0));
        assertThat(write(T, 1, new Position("p0", 11))).isEqualTo(new Result(false, 1, 0));

        assertThat(store.sum(S, Grain.MINUTE, T, T + 1)).isEqualTo(new Parts(3, 0));
        assertThat(store.position(S, "p0")).hasValue(11);
    }

    @Test
    void aClaimedBatchIsADuplicate() {
        Batch b = new Batch(List.of(), "k", Long.MAX_VALUE);
        assertThat(store.apply(b).duplicate()).isFalse();
        assertThat(store.apply(b)).isEqualTo(Result.DUPLICATE);
        assertThat(store.claim("k", 1)).isFalse();
        assertThat(store.claim("k2", 1)).isTrue();
    }

    @Test
    void aSnapshotIsStoredOnlyForTheCurrentVersionAndNeverOverAFresherOne() {
        write(T, 1);
        RingSnapshot ring = new RingSnapshot(Grain.MINUTE, 5, T - 240_000, T, new Parts(1, 0), store.version(S));

        assertThat(store.putSnapshot(S, ring.key(), ring)).isTrue();
        assertThat(store.putSnapshot(S, ring.key(), new RingSnapshot(Grain.MINUTE, 5, T - 300_000, T - 60_000,
                Parts.ZERO, store.version(S)))).isFalse();

        write(T, 2); // patches the stored one and moves its version on
        assertThat(store.snapshot(S, ring.key()).sum()).isEqualTo(new Parts(3, 0));
        assertThat(store.putSnapshot(S, ring.key(), ring)).isFalse(); // computed for the old version
    }

    @Test
    void aSnapshotRoundTripsBucketsAnchorsPositionsAndClaims() throws IOException {
        write(T, 5, new Position("p0", 7));
        write(T + 60_000, -2);
        store.apply(new Batch(List.of(new SeriesBatch(S, List.of(new BucketWrite(Grain.ALL, 0, new Parts(5, 2))),
                Long.MAX_VALUE, T, null)), "claim-1", 99));
        Labels other = Labels.of(Labels.METRIC_NAME, "y_total", "card", "tx_ab\"c");
        store.apply(new Batch(List.of(new SeriesBatch(other, List.of(new BucketWrite(Grain.DAY, T, new Parts(1, 0))),
                T, T, null)), null, 0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.writeSnapshot(out);
        MemoryCounterStore copy = MemoryCounterStore.readSnapshot(new ByteArrayInputStream(out.toByteArray()));

        assertThat(dump(copy)).containsExactlyInAnyOrderElementsOf(dump(store));
        assertThat(copy.seriesCount()).isEqualTo(2);
        assertThat(copy.bucketCount()).isEqualTo(store.bucketCount());
        assertThat(copy.labelValues("card")).containsExactly("tx_ab\"c");
        assertThat(copy.position(S, "p0")).hasValue(7);
        assertThat(copy.apply(new Batch(List.of(), "claim-1", 99)).duplicate()).isTrue();
    }

    @Test
    void aVersionOneSnapshotStillReads() throws IOException {
        // the 0.1 format: no positions
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x54545344);
            out.writeInt(1);
            String[] table = {Labels.METRIC_NAME, "x_total", "a", "1"};
            out.writeInt(table.length);
            for (String s : table) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                out.writeInt(b.length);
                out.write(b);
            }
            out.writeInt(1);           // series
            out.writeInt(2);           // labels
            out.writeInt(0);
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            out.writeLong(T);          // first added
            out.writeLong(T);          // last write
            out.writeInt(1);           // grains
            out.writeByte(Grain.MINUTE.ordinal());
            out.writeInt(1);
            out.writeLong(T);
            out.writeLong(4);
            out.writeLong(1);
            out.writeInt(0);           // claims
        }
        MemoryCounterStore read = MemoryCounterStore.readSnapshot(new ByteArrayInputStream(bytes.toByteArray()));

        assertThat(read.sum(S, Grain.MINUTE, T, T + 1)).isEqualTo(new Parts(4, 1));
        assertThat(read.firstAdded(S)).hasValue(T);
    }

    @Test
    void notASnapshot() {
        assertThatThrownBy(() -> MemoryCounterStore.readSnapshot(new ByteArrayInputStream(new byte[]{1, 2, 3, 4})))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aStorageKeySplitsKeyLabelsFromFieldLabels() {
        Labels series = Labels.of(Labels.METRIC_NAME, "amount_total", "card", "c1", "currency", "EUR",
                "status", "O|K");

        StorageKey k = StorageKey.of(series, List.of(Labels.METRIC_NAME, "currency", "status"));

        assertThat(k.key()).isEqualTo(Labels.of("card", "c1"));
        assertThat(k.field()).isEqualTo("amount_total|EUR|O\\|K");
        assertThat(StorageKey.of(Labels.of(Labels.METRIC_NAME, "n_total", "card", "c1"),
                List.of(Labels.METRIC_NAME, "currency")).field()).isEqualTo("n_total|");
    }

    private static List<String> dump(MemoryCounterStore s) {
        List<String> out = new ArrayList<>();
        s.export(new StoreSink() {
            @Override
            public void bucket(Labels series, Grain grain, long start, Parts parts) {
                out.add(series + " " + grain + " " + start + " " + parts);
            }

            @Override
            public void firstAdded(Labels series, long occurredAtMs) {
                out.add(series + " first " + occurredAtMs);
            }

            @Override
            public void position(Labels series, String stream, long offset) {
                out.add(series + " position " + stream + " " + offset);
            }

            @Override
            public void claim(String key, long expiresAtMs) {
                out.add("claim " + key + " " + expiresAtMs);
            }
        });
        return out;
    }

    @Test
    void writersAndReadersTogetherLoseNothing() throws Exception {
        TestClock clock = new TestClock(T);
        TinyTsdb db = TinyTsdb.builder().clock(clock).ringSnapshots(true).tier(Grain.MINUTE, Duration.ofHours(2)).build();
        int writers = 4;
        int perWriter = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int id = w;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perWriter; i++) {
                        db.add("c_total", Labels.of("user", "" + (i % 50), "w", "" + id), T - (i % 30) * 1_000L, 1);
                    }
                }));
            }
            for (int r = 0; r < 2; r++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 2_000; i++) {
                        db.read("c_total", Labels.of("user", "" + (i % 50), "w", "0"), Window.rolling(5, Grain.MINUTE));
                        db.query("sum by (w) (increase(c_total[5m]))");
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.query("sum(c_total)").vector().get(0).value()).isEqualTo(writers * perWriter);
        assertThat(db.query("sum(increase(c_total[5m]))").vector().get(0).value()).isEqualTo(writers * perWriter);
        MemoryCounterStore mem = (MemoryCounterStore) db.store();
        for (int u = 0; u < 50; u++) {
            for (int w = 0; w < writers; w++) {
                Labels series = Labels.of("user", "" + u, "w", "" + w).with(Labels.METRIC_NAME, "c_total");
                RingSnapshot ring = mem.snapshot(series, RingSnapshot.key(Grain.MINUTE, 5));
                if (ring != null) {
                    assertThat(ring.sum()).isEqualTo(mem.sum(series, Grain.MINUTE, ring.fromStart(),
                            Grain.MINUTE.end(ring.currentStart(), db.zone())));
                }
            }
        }
    }
}
