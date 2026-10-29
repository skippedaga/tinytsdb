package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.TsdbListener.RingOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rolling windows with stored aggregates give the same answers as summing the buckets: patched by writes, moved
 * forward by reads, recomputed when too far behind, never stored stale.
 */
class RingSnapshotTest {

    static final long T = TinyTsdbTest.T;
    static final long MIN = TinyTsdbTest.MIN;
    static final Labels USER = Labels.of("user", "7");
    static final Window FIVE = Window.rolling(5, Grain.MINUTE);

    private final TestClock clock = new TestClock(T);
    private final List<RingOutcome> outcomes = new ArrayList<>();

    private TinyTsdb db(boolean rings, CounterStore store) {
        return TinyTsdb.builder().clock(clock).zone(TinyTsdbTest.KYIV)
                .tier(Grain.MINUTE, Duration.ofHours(2))
                .tier(Grain.HOUR, Duration.ofDays(2))
                .tier(Grain.DAY, Duration.ofDays(30))
                .ringSnapshots(rings)
                .store(store)
                .listener(new TsdbListener() {
                    @Override
                    public void onRing(RingOutcome outcome) {
                        outcomes.add(outcome);
                    }
                })
                .build();
    }

    private final TinyTsdb rings = db(true, new MemoryCounterStore());
    private final TinyTsdb plain = db(false, new MemoryCounterStore());

    private void add(long at, long delta) {
        rings.add("tx_total", USER, at, delta);
        plain.add("tx_total", USER, at, delta);
    }

    private long read(Window w) {
        Reading r = rings.read("tx_total", USER, w);
        assertThat(r).isEqualTo(plain.read("tx_total", USER, w));
        return r.net();
    }

    @Test
    void missThenKeepThenPatchedByAWrite() {
        add(T - MIN, 1);

        assertThat(read(FIVE)).isEqualTo(1);
        assertThat(read(FIVE)).isEqualTo(1);
        add(T, 2);
        add(T - 30 * MIN, 100); // outside the window: the aggregate is not touched
        assertThat(read(FIVE)).isEqualTo(3);

        assertThat(outcomes).containsExactly(RingOutcome.MISS, RingOutcome.KEEP, RingOutcome.KEEP);
    }

    @Test
    void aFewStepsLaterTheAggregateMovesForward() {
        add(T - 4 * MIN, 1);  // leaves after one step
        add(T - MIN, 1);
        read(FIVE);

        clock.advance(2 * MIN);
        add(T + MIN + 10_000, 5); // in a step the stored aggregate has not reached: picked up on the move
        assertThat(read(FIVE)).isEqualTo(6);
        assertThat(outcomes).containsExactly(RingOutcome.MISS, RingOutcome.ADVANCE);
    }

    @Test
    void farBehindItIsRecomputed() {
        add(T, 1);
        read(FIVE);

        clock.advance(30 * MIN);
        add(clock.millis(), 1);
        assertThat(read(FIVE)).isEqualTo(1);
        assertThat(outcomes).containsExactly(RingOutcome.MISS, RingOutcome.RECOMPUTE);
    }

    @Test
    void aReadForAnotherTimeIsNeverStored() {
        add(T - 10 * MIN, 1);

        Reading past = rings.read("tx_total", USER, FIVE, T - 8 * MIN);
        assertThat(past.net()).isEqualTo(1);
        assertThat(rings.store().snapshot(USER.with(Labels.METRIC_NAME, "tx_total"), RingSnapshot.key(Grain.MINUTE, 5)))
                .isNull();
    }

    /** A store that lets one write in between the read of the buckets and the write-back of the aggregate. */
    private static final class RacingStore extends DelegatingStore {
        Runnable once;

        RacingStore(CounterStore inner) {
            super(inner);
        }

        @Override
        public Parts sum(Labels series, Grain grain, long fromStart, long toStart) {
            Parts p = super.sum(series, grain, fromStart, toStart);
            if (once != null) {
                Runnable r = once;
                once = null;
                r.run();
            }
            return p;
        }
    }

    @Test
    void aWriteDuringTheReadKeepsAStaleAggregateFromBeingStored() {
        RacingStore racing = new RacingStore(new MemoryCounterStore());
        TinyTsdb db = db(true, racing);
        db.add("tx_total", USER, T, 1);
        racing.once = () -> db.add("tx_total", USER, T, 10);

        assertThat(db.read("tx_total", USER, FIVE).net()).isEqualTo(11);
        assertThat(db.read("tx_total", USER, FIVE).net()).isEqualTo(11);
        assertThat(outcomes).containsExactly(RingOutcome.MISS, RingOutcome.KEEP);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void randomWritesAndClockMovesGiveTheSameAnswersAsSummingTheBuckets(long seed) {
        Random random = new Random(seed);
        List<Window> windows = List.of(FIVE, Window.rolling(30, Grain.MINUTE), Window.rolling(1, Grain.MINUTE),
                Window.rolling(6, Grain.HOUR), Window.rolling(3, Grain.DAY));
        for (int step = 0; step < 400; step++) {
            int what = random.nextInt(10);
            if (what < 5) {
                // mostly now, sometimes late, sometimes a little ahead
                long at = clock.millis() - (random.nextInt(8) == 0 ? random.nextInt(90) * MIN : random.nextInt(60_000))
                        + (random.nextInt(20) == 0 ? MIN : 0);
                add(at, random.nextInt(5) == 0 ? -random.nextInt(3) - 1 : random.nextInt(3) + 1);
            } else if (what < 8) {
                read(windows.get(random.nextInt(windows.size())));
            } else {
                clock.advance(random.nextInt(4) == 0 ? random.nextInt(20) * MIN : random.nextInt(90_000));
            }
        }
        for (Window w : windows) {
            read(w);
        }
        assertThat(outcomes).contains(RingOutcome.KEEP, RingOutcome.ADVANCE);
    }

    /** Passes everything through; tests override what they need. */
    static class DelegatingStore implements CounterStore {
        private final CounterStore inner;

        DelegatingStore(CounterStore inner) {
            this.inner = inner;
        }

        @Override
        public boolean apply(Batch batch) {
            return inner.apply(batch);
        }

        @Override
        public void releaseClaim(String claimKey) {
            inner.releaseClaim(claimKey);
        }

        @Override
        public Parts read(Labels series, Grain grain, long start) {
            return inner.read(series, grain, start);
        }

        @Override
        public Parts sum(Labels series, Grain grain, long fromStart, long toStart) {
            return inner.sum(series, grain, fromStart, toStart);
        }

        @Override
        public SortedMap<Long, Parts> buckets(Labels series, Grain grain, long fromStart, long toStart) {
            return inner.buckets(series, grain, fromStart, toStart);
        }

        @Override
        public java.util.OptionalLong firstAdded(Labels series) {
            return inner.firstAdded(series);
        }

        @Override
        public long version(Labels series) {
            return inner.version(series);
        }

        @Override
        public List<Labels> series(List<Matcher> matchers, int limit) {
            return inner.series(matchers, limit);
        }

        @Override
        public java.util.Set<String> labelValues(String label) {
            return inner.labelValues(label);
        }

        @Override
        public RingSnapshot snapshot(Labels series, String key) {
            return inner.snapshot(series, key);
        }

        @Override
        public boolean putSnapshot(Labels series, String key, RingSnapshot snapshot) {
            return inner.putSnapshot(series, key, snapshot);
        }

        @Override
        public void expire(Grain grain, long beforeStart) {
            inner.expire(grain, beforeStart);
        }

        @Override
        public int expireClaims(long nowMs) {
            return inner.expireClaims(nowMs);
        }

        @Override
        public int removeIdle(String label, long lastWriteBeforeMs) {
            return inner.removeIdle(label, lastWriteBeforeMs);
        }

        @Override
        public void export(StoreSink sink) {
            inner.export(sink);
        }

        @Override
        public StoreSink mergeMaxSink() {
            return inner.mergeMaxSink();
        }
    }
}
