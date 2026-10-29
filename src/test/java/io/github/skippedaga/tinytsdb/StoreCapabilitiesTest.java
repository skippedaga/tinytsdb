package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.CounterStore.Atomicity;
import io.github.skippedaga.tinytsdb.CounterStore.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TinyTsdb over a store with only the core SPI, like a remote key-value store: bulk reads in one round trip, writes
 * atomic per series with stream offsets, exact selectors only, no maintenance.
 */
class StoreCapabilitiesTest {

    static final long T = TinyTsdbTest.T;
    static final long MIN = TinyTsdbTest.MIN;
    static final Labels USER = Labels.of("user", "7");

    private final TestClock clock = new TestClock(T);

    private TinyTsdb db(CounterStore store) {
        return TinyTsdb.builder().clock(clock).store(store)
                .tier(Grain.MINUTE, Duration.ofHours(26))
                .tier(Grain.HOUR, Duration.ofDays(32))
                .tier(Grain.DAY, Duration.ofDays(400))
                .build();
    }

    @Test
    void everyWindowOfASeriesIsOneReadAndAgreesWithTheFullStore() {
        CoreOnlyStore core = new CoreOnlyStore(Atomicity.BATCH);
        TinyTsdb remote = db(core);
        TinyTsdb local = db(new MemoryCounterStore());
        for (int i = 0; i < 200; i++) {
            long at = T - i * 37 * MIN;
            for (TinyTsdb d : List.of(remote, local)) {
                d.add("tx_total", USER, at, i % 7 == 0 ? -1 : 2);
            }
        }
        List<Window> windows = List.of(Window.rolling(5, Grain.MINUTE), Window.rolling(1440, Grain.MINUTE),
                Window.rolling(720, Grain.HOUR), Window.calendar("d:2026-10-28"), Window.range(T - 3 * 86_400_000L, T),
                Window.allTime());

        core.readManyCalls.set(0);
        Map<Window, Reading> got = remote.read("tx_total", USER, windows, T);

        assertThat(core.readManyCalls).hasValue(1);
        Map<Window, Reading> expected = local.read("tx_total", USER, windows, T);
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void aGroupedReadOfManySeriesIsOneReadToo() {
        CoreOnlyStore core = new CoreOnlyStore(Atomicity.BATCH);
        TinyTsdb remote = db(core);
        remote.add("tx_total", Labels.of("user", "7", "status", "OK"), T, 1);
        remote.add("tx_total", Labels.of("user", "7", "status", "FAIL"), T, 1);

        // without an index the filter names one series exactly: {user="7"} alone is a series of its own, empty
        assertThat(remote.readGrouped("tx_total", Labels.of("user", "7"), List.of("status"), Window.allTime())
                .get(Labels.empty()).net()).isZero();
        core.readManyCalls.set(0);
        Map<Labels, Reading> one = remote.readGrouped("tx_total", Labels.of("user", "7", "status", "OK"),
                List.of("status"), Window.allTime());
        assertThat(one.get(Labels.of("status", "OK")).net()).isEqualTo(1);
        assertThat(core.readManyCalls).hasValue(1);
    }

    @Test
    void promqlWithoutAnIndexNeedsExactSelectors() {
        TinyTsdb remote = db(new CoreOnlyStore(Atomicity.BATCH));
        remote.add("tx_total", USER, T, 3);

        assertThat(remote.query("increase(tx_total{user=\"7\"}[5m])").vector().get(0).value()).isEqualTo(3);
        assertThat(remote.query("tx_total{user=\"7\"}").vector().get(0).value()).isEqualTo(3);
        assertThatThrownBy(() -> remote.query("sum(tx_total{user=~\"7|8\"})"))
                .isInstanceOf(QueryException.class).hasMessageContaining("no label index");
    }

    @Test
    void maintainIsANoOpForAStoreThatPrunesItself() {
        TinyTsdb remote = db(new CoreOnlyStore(Atomicity.BATCH));
        remote.add("tx_total", USER, T, 1);
        clock.advance(Duration.ofDays(3).toMillis());

        assertThat(remote.maintain()).isEqualTo(new TinyTsdb.Maintenance(0, 0));
        assertThatThrownBy(() -> remote.rebuildFrom(new MemoryCounterStore())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyAtSkipsTheSeriesThatAlreadyTookTheOffset() {
        CoreOnlyStore core = new CoreOnlyStore(Atomicity.SERIES);
        TinyTsdb db = db(core);
        List<Increment> report = List.of(
                Increment.of("n_total", Labels.of("card", "c1"), T, 1),
                Increment.of("n_ok_total", Labels.of("card", "c1"), T, 1),
                Increment.of("amount_total", Labels.of("card", "c1"), T, 12_50));

        core.failAfterSeries = 2; // the connection drops after two of the three series
        assertThatThrownBy(() -> db.applyAt("conversions-0", 41, report)).isInstanceOf(IllegalStateException.class);
        Result retry = db.applyAt("conversions-0", 41, report);

        assertThat(retry).isEqualTo(new Result(false, 1, 2));
        for (String metric : List.of("n_total", "n_ok_total")) {
            assertThat(db.read(metric, Labels.of("card", "c1"), Window.allTime()).net()).isEqualTo(1);
        }
        assertThat(db.read("amount_total", Labels.of("card", "c1"), Window.allTime()).net()).isEqualTo(12_50);
        // a replay of an old offset changes nothing
        assertThat(db.applyAt("conversions-0", 40, report)).isEqualTo(new Result(false, 0, 3));
    }

    @Test
    void applyOnceOnASeriesAtomicStoreReleasesTheClaimWhenTheWritesFail() {
        CoreOnlyStore core = new CoreOnlyStore(Atomicity.SERIES);
        TinyTsdb db = db(core);
        List<Increment> batch = List.of(Increment.of("n_total", USER, T, 1), Increment.of("a_total", USER, T, 5));

        core.failAfterSeries = 0;
        assertThatThrownBy(() -> db.applyOnce("pay-1:APPROVED:1", batch)).isInstanceOf(IllegalStateException.class);
        assertThat(db.applyOnce("pay-1:APPROVED:1", batch)).isTrue();
        assertThat(db.applyOnce("pay-1:APPROVED:1", batch)).isFalse();
        assertThat(db.read("a_total", USER, Window.allTime()).net()).isEqualTo(5);
    }
}
