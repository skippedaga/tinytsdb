package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Batch;
import io.github.skippedaga.tinytsdb.CounterStore.BucketWrite;
import io.github.skippedaga.tinytsdb.CounterStore.SeriesWrite;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    private void write(long start, long delta) {
        store.apply(new Batch(List.of(new BucketWrite(S, Grain.MINUTE, start, Parts.of(delta))),
                List.of(new SeriesWrite(S, start, delta > 0, T)), null, 0));
    }

    @Test
    void bucketsSumAndRangeInAnyWriteOrder() {
        write(T + 120_000, 1);
        write(T, 2);
        write(T + 60_000, -3);
        write(T, 4);

        assertThat(store.read(S, Grain.MINUTE, T)).isEqualTo(new Parts(6, 0));
        assertThat(store.sum(S, Grain.MINUTE, T, T + 120_000)).isEqualTo(new Parts(6, 3));
        assertThat(store.buckets(S, Grain.MINUTE, T, Long.MAX_VALUE)).containsOnlyKeys(T, T + 60_000, T + 120_000);
        assertThat(store.version(S)).isEqualTo(4);
        assertThat(store.firstAdded(S)).hasValue(T);
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
    void aSnapshotRoundTripsBucketsAnchorsAndClaims() throws IOException {
        write(T, 5);
        write(T + 60_000, -2);
        store.apply(new Batch(List.of(new BucketWrite(S, Grain.ALL, 0, new Parts(5, 2))), List.of(), "claim-1", 99));
        Labels other = Labels.of(Labels.METRIC_NAME, "y_total", "card", "tx_ab\"c");
        store.apply(new Batch(List.of(new BucketWrite(other, Grain.DAY, T, new Parts(1, 0))),
                List.of(new SeriesWrite(other, T, true, T)), null, 0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.writeSnapshot(out);
        MemoryCounterStore copy = MemoryCounterStore.readSnapshot(new ByteArrayInputStream(out.toByteArray()));

        List<String> original = dump(store);
        assertThat(dump(copy)).containsExactlyInAnyOrderElementsOf(original);
        assertThat(copy.seriesCount()).isEqualTo(2);
        assertThat(copy.bucketCount()).isEqualTo(store.bucketCount());
        assertThat(copy.labelValues("card")).containsExactly("tx_ab\"c");
        assertThat(copy.apply(new Batch(List.of(), List.of(), "claim-1", 99))).isFalse();
    }

    @Test
    void notASnapshot() {
        assertThatThrownBy(() -> MemoryCounterStore.readSnapshot(new ByteArrayInputStream(new byte[]{1, 2, 3, 4})))
                .isInstanceOf(IOException.class);
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
        // every stored aggregate agrees with its buckets
        for (int u = 0; u < 50; u++) {
            for (int w = 0; w < writers; w++) {
                Labels l = Labels.of("user", "" + u, "w", "" + w);
                Labels series = l.with(Labels.METRIC_NAME, "c_total");
                RingSnapshot ring = db.store().snapshot(series, RingSnapshot.key(Grain.MINUTE, 5));
                if (ring != null) {
                    assertThat(ring.sum()).isEqualTo(db.store().sum(series, Grain.MINUTE, ring.fromStart(),
                            Grain.MINUTE.end(ring.currentStart(), db.zone())));
                }
            }
        }
    }
}
