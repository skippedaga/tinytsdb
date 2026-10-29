package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Writes, windows, idempotency, maintenance. The clock starts at Thursday 2026-10-29 12:00 UTC (14:00 in Kyiv). */
class TinyTsdbTest {

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    static final long T = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();
    static final long SEC = 1_000;
    static final long MIN = 60 * SEC;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;

    private final TestClock clock = new TestClock(T);

    private TinyTsdb.Builder builder() {
        return TinyTsdb.builder().zone(KYIV).clock(clock)
                .tier(Grain.MINUTE, Duration.ofHours(48))
                .tier(Grain.HOUR, Duration.ofDays(62))
                .tier(Grain.DAY, Duration.ofDays(3 * 365))
                .tier(Grain.MONTH, Duration.ofDays(10 * 365));
    }

    private final TinyTsdb db = builder().build();
    private static final Labels USER = Labels.of("user", "7");

    private long net(Window w) {
        return db.read("tx_total", USER, w).net();
    }

    @Test
    void anIncrementLandsAtItsEventTimeInEveryTierAndInAllTime() {
        db.add("tx_total", USER, T - 90 * SEC, 3);

        assertThat(net(Window.calendar("m:2026-10-29T11:58"))).isEqualTo(3);
        assertThat(net(Window.calendar("h:2026-10-29T11"))).isEqualTo(3);
        assertThat(net(Window.calendar("d:2026-10-29"))).isEqualTo(3);
        assertThat(net(Window.calendar("mo:2026-10"))).isEqualTo(3);
        assertThat(net(Window.allTime())).isEqualTo(3);
        assertThat(net(Window.calendar("m:2026-10-29T11:59"))).isZero();
    }

    @Test
    void aNegativeDeltaIsARetractionKeptApart() {
        db.add("amount_total", USER, T - MIN, 10_000);
        db.add("amount_total", USER, T - MIN, -2_500);

        Reading r = db.read("amount_total", USER, Window.calendar("d:2026-10-29"));
        assertThat(r.added()).isEqualTo(10_000);
        assertThat(r.retracted()).isEqualTo(2_500);
        assertThat(r.net()).isEqualTo(7_500);
        assertThat(r.exact()).isTrue();
    }

    @Test
    void aLateCorrectionGoesBackToTheEventsBucketsThatAreStillKept() {
        long tenDaysAgo = T - 10 * DAY;
        db.add("tx_total", USER, tenDaysAgo, 5);
        db.add("tx_total", USER, tenDaysAgo, -2);

        assertThat(net(Window.calendar(Grain.DAY.id(tenDaysAgo, KYIV)))).isEqualTo(3);
        assertThat(net(Window.calendar(Grain.HOUR.id(tenDaysAgo, KYIV)))).isEqualTo(3);
        // the minute tier keeps 48 hours: nothing was written there, and the reading says it cannot be exact
        Reading minute = db.read("tx_total", USER, Window.calendar(Grain.MINUTE.id(tenDaysAgo, KYIV)));
        assertThat(minute.net()).isZero();
        assertThat(minute.exact()).isFalse();
    }

    @Test
    void anEventOlderThanEveryTierStillCountsInAllTime() {
        db.add("tx_total", USER, T - 20 * 365 * DAY, 1);

        assertThat(net(Window.allTime())).isEqualTo(1);
        assertThat(db.store().buckets(USER.with(Labels.METRIC_NAME, "tx_total"), Grain.MONTH, Long.MIN_VALUE,
                Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void aRollingWindowIsTheLastStepsIncludingTheOneInProgress() {
        clock.set(T + 30 * SEC);                    // 12:00:30, minute in progress 12:00
        db.add("tx_total", USER, T - 4 * MIN - 1, 1);   // 11:55:59.999 — outside
        db.add("tx_total", USER, T - 4 * MIN, 1);       // 11:56 — first step
        db.add("tx_total", USER, T + 10 * SEC, 1);      // 12:00:10 — step in progress

        Reading r = db.read("tx_total", USER, Window.rolling(Duration.ofMinutes(5), Grain.MINUTE));
        assertThat(r.net()).isEqualTo(2);
        assertThat(r.fromMs()).isEqualTo(T - 4 * MIN);
        assertThat(r.toMs()).isEqualTo(T + MIN);
    }

    @Test
    void aRollingWindowOfZonedDaysCountsCalendarDays() {
        // seven Kyiv days ending today (Thursday 29th): 23rd..29th; the 25th is the 25-hour DST day
        db.add("tx_total", USER, Instant.parse("2026-10-22T20:59:00Z").toEpochMilli(), 1); // 22nd 23:59 local (UTC+3)
        db.add("tx_total", USER, Instant.parse("2026-10-22T21:00:00Z").toEpochMilli(), 1); // 23rd 00:00 local
        db.add("tx_total", USER, Instant.parse("2026-10-25T23:30:00Z").toEpochMilli(), 1); // 26th 01:30 local (UTC+2)

        Reading r = db.read("tx_total", USER, Window.rolling(Duration.ofDays(7), Grain.DAY));
        assertThat(r.net()).isEqualTo(2);
        assertThat(r.fromMs()).isEqualTo(Instant.parse("2026-10-22T21:00:00Z").toEpochMilli()); // 23rd, UTC+3
    }

    @Test
    void aRangeIsBuiltFromWholeBucketsAndSaysWhenAnEdgeCouldNotBe() {
        for (int d = 0; d < 5; d++) {
            db.add("tx_total", USER, T - d * DAY - 30 * MIN, 1);
        }
        // whole hours, three days back: hours from the hour tier, the middle from whole Kyiv days
        Reading aligned = db.read("tx_total", USER, Window.range(T - 3 * DAY - HOUR, T));
        assertThat(aligned.net()).isEqualTo(4);
        assertThat(aligned.exact()).isTrue();

        // starts at 10:50 three days back: the first ten minutes are older than the minute tier keeps
        Reading ragged = db.read("tx_total", USER, Window.range(T - 3 * DAY - 70 * MIN, T));
        assertThat(ragged.exact()).isFalse();
        assertThat(ragged.net()).isEqualTo(4);

        // inside the minute tier any minute edge is exact
        Reading recent = db.read("tx_total", USER, Window.range(T - 31 * MIN, T - 29 * MIN));
        assertThat(recent.net()).isEqualTo(1);
        assertThat(recent.exact()).isTrue();
    }

    @Test
    void anAnchoredWindowOpensAtTheFirstPositiveEventOfTheAnchorMetric() {
        Labels card = Labels.of("card", "c1");
        Window firstWeek = Window.anchored("approved_total", Duration.ofDays(7));

        assertThat(db.read("amount_total", card, firstWeek).net()).isZero();

        db.add("amount_total", card, T - 3 * DAY, 100);           // before the anchor
        db.add("approved_total", card, T - 2 * DAY, 1);           // the anchor
        db.add("amount_total", card, T - 2 * DAY, 200);
        db.add("amount_total", card, T, 300);

        Reading r = db.read("amount_total", card, firstWeek);
        assertThat(r.net()).isEqualTo(500);
        assertThat(r.fromMs()).isEqualTo(T - 2 * DAY);

        // a late event that happened earlier moves the anchor back
        db.add("approved_total", card, T - 3 * DAY, 1);
        assertThat(db.read("amount_total", card, firstWeek).net()).isEqualTo(600);
    }

    @Test
    void aRetractedAnchorMovesOnlyWhenAskedTo() {
        Labels card = Labels.of("card", "c1");
        db.add("approved_total", card, T - 2 * DAY, 1);
        db.add("approved_total", card, T - DAY, 1);
        db.add("amount_total", card, T - 2 * DAY, 200);
        db.add("amount_total", card, T - DAY, 300);
        db.add("approved_total", card, T - 2 * DAY, -1); // the first approval is reversed

        Window kept = Window.anchored("approved_total", Duration.ofHours(30));
        Window moved = new Window.Anchored("approved_total", Duration.ofHours(30), true);

        assertThat(db.read("amount_total", card, kept).net()).isEqualTo(500);
        Reading r = db.read("amount_total", card, moved);
        assertThat(r.net()).isEqualTo(300);
        assertThat(r.fromMs()).isEqualTo(T - DAY);
    }

    @Test
    void applyOnceWritesABatchAtMostOncePerKey() {
        List<Increment> batch = List.of(
                Increment.of("tx_total", USER, T, 1),
                Increment.of("amount_total", USER, T, 1_500));

        assertThat(db.applyOnce("pay-1:APPROVED:1", batch)).isTrue();
        assertThat(db.applyOnce("pay-1:APPROVED:1", batch)).isFalse();
        assertThat(net(Window.allTime())).isEqualTo(1);
        assertThat(db.read("amount_total", USER, Window.allTime()).net()).isEqualTo(1_500);

        // the caller failed after the claim: releasing it lets the retry through
        db.releaseClaim("pay-1:APPROVED:1");
        assertThat(db.applyOnce("pay-1:APPROVED:1", batch)).isTrue();
        assertThat(net(Window.allTime())).isEqualTo(2);
    }

    @Test
    void claimsExpireAfterTheirTtl() {
        TinyTsdb short_ = builder().claimTtl(Duration.ofHours(1)).build();
        short_.applyOnce("k", List.of(Increment.of("tx_total", USER, T, 1)));

        clock.advance(59 * MIN);
        assertThat(short_.maintain().expiredClaims()).isZero();
        clock.advance(2 * MIN);
        assertThat(short_.maintain().expiredClaims()).isEqualTo(1);
        assertThat(short_.applyOnce("k", List.of(Increment.of("tx_total", USER, T, 1)))).isTrue();
    }

    @Test
    void maintainDropsBucketsPastEachTiersRetention() {
        db.add("tx_total", USER, T, 1);
        Labels series = USER.with(Labels.METRIC_NAME, "tx_total");

        clock.advance(49 * HOUR);
        db.maintain();

        assertThat(db.store().buckets(series, Grain.MINUTE, Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty();
        assertThat(db.store().buckets(series, Grain.HOUR, Long.MIN_VALUE, Long.MAX_VALUE)).hasSize(1);
        assertThat(net(Window.calendar("h:2026-10-29T12"))).isEqualTo(1);
    }

    @Test
    void readGroupedSumsPerLabelValue() {
        db.add("tx_total", Labels.of("user", "1", "status", "OK"), T, 2);
        db.add("tx_total", Labels.of("user", "1", "status", "FAIL"), T, 1);
        db.add("tx_total", Labels.of("user", "2", "status", "OK"), T, 5);

        Map<Labels, Reading> byStatus = db.readGrouped("tx_total", Labels.of("user", "1"), List.of("status"),
                Window.calendar("d:2026-10-29"));
        assertThat(byStatus).hasSize(2);
        assertThat(byStatus.get(Labels.of("status", "OK")).net()).isEqualTo(2);
        assertThat(byStatus.get(Labels.of("status", "FAIL")).net()).isEqualTo(1);

        Map<Labels, Reading> all = db.readGrouped("tx_total", Labels.empty(), List.of(), Window.allTime());
        assertThat(all.get(Labels.empty()).net()).isEqualTo(8);
    }

    @Test
    void labelTransformsRunBeforeAnythingIsStored() {
        byte[] key = "k3y".getBytes(StandardCharsets.UTF_8);
        TinyTsdb hashed = builder()
                .transform("card", LabelTransform.hmac(key, "tx_"))
                .transform("bin", LabelTransform.prefix(6))
                .transform("band", LabelTransform.bucket(1_000))
                .build();
        hashed.add("tx_total", Labels.of("card", "card-ab12cd", "bin", "ABCDEFGH", "band", "2499"), T, 1);
        hashed.add("tx_total", Labels.of("card", "card-ab12cd", "bin", "ABCDEFGH", "band", "n/a"), T, 1);

        List<Labels> stored = hashed.store().series(List.of(Matcher.of(Matcher.Type.EQUAL, Labels.METRIC_NAME,
                "tx_total")), 10);
        assertThat(stored).allSatisfy(l -> {
            assertThat(l.get("card")).startsWith("tx_").doesNotContain("ab12");
            assertThat(l.get("bin")).isEqualTo("ABCDEF");
        });
        assertThat(stored).extracting(l -> l.get("band")).containsExactlyInAnyOrder("2000", "");
        // reads and queries take the raw value and transform it the same way
        assertThat(hashed.read("tx_total", Labels.of("card", "card-ab12cd", "bin", "ABCDEFGH", "band", "2400"),
                Window.allTime()).net()).isEqualTo(1);
        assertThat(hashed.query("sum(tx_total{card=\"card-ab12cd\"})").vector().get(0).value()).isEqualTo(2);
        assertThat(LabelTransform.hmac(key, "tx_").apply("x")).hasSize(3 + 43);
        assertThat(LabelTransform.parse("upper", null, null).apply("ab")).isEqualTo("AB");
        assertThat(LabelTransform.bucket(10).apply("-5")).isEqualTo("-10");
    }

    @Test
    void aCardinalityCapSendsNewValuesToTheOverflowSeries() {
        List<String> overflows = new ArrayList<>();
        TinyTsdb capped = builder()
                .rule(CardinalityRule.cap("card", 2))
                .listener(new TsdbListener() {
                    @Override
                    public void onOverflow(String label) {
                        overflows.add(label);
                    }
                })
                .build();
        for (String c : List.of("a", "b", "c", "d", "a")) {
            capped.add("tx_total", Labels.of("card", c), T, 1);
        }

        assertThat(capped.store().labelValues("card")).containsExactlyInAnyOrder("a", "b", CardinalityRule.DEFAULT_OVERFLOW);
        assertThat(capped.read("tx_total", Labels.of("card", "a"), Window.allTime()).net()).isEqualTo(2);
        assertThat(capped.read("tx_total", Labels.of("card", CardinalityRule.DEFAULT_OVERFLOW), Window.allTime()).net())
                .isEqualTo(2);
        assertThat(overflows).containsExactly("card", "card");
    }

    @Test
    void idleSeriesOfAWatchedLabelAreRemovedWhole() {
        TinyTsdb ttl = builder().rule(CardinalityRule.idle("card", Duration.ofDays(30))).build();
        ttl.add("tx_total", Labels.of("card", "old"), T, 1);
        clock.advance(20 * DAY);
        ttl.add("tx_total", Labels.of("card", "new"), clock.millis(), 1);
        ttl.add("tx_total", Labels.of("user", "kept"), T, 1);
        clock.advance(11 * DAY);

        assertThat(ttl.maintain().removedSeries()).isEqualTo(1);
        assertThat(ttl.store().labelValues("card")).containsExactly("new");
        assertThat(ttl.read("tx_total", Labels.of("user", "kept"), Window.allTime()).net()).isEqualTo(1);
    }

    @Test
    void aRebuildMergesAColdStoreByMaxPerPart() {
        TinyTsdb cold = builder().build();
        TinyTsdb hot = builder().build();
        List<Increment> events = List.of(
                Increment.of("tx_total", USER, T - 3 * DAY, 4),
                Increment.of("tx_total", USER, T - 3 * DAY, -1),
                Increment.of("tx_total", USER, T - MIN, 2));
        cold.applyOnce("e1", events.subList(0, 2));
        cold.applyOnce("e2", events.subList(2, 3));
        hot.applyOnce("e2", events.subList(2, 3)); // the hot copy saw only the later event

        hot.rebuildFrom(cold.store());

        for (Window w : List.of(Window.allTime(), Window.calendar(Grain.DAY.id(T - 3 * DAY, KYIV)),
                Window.rolling(Duration.ofMinutes(5), Grain.MINUTE))) {
            assertThat(hot.read("tx_total", USER, w)).as(w.toString()).isEqualTo(cold.read("tx_total", USER, w));
        }
        assertThat(hot.applyOnce("e1", events.subList(0, 2))).isFalse();
        assertThat(hot.store().firstAdded(USER.with(Labels.METRIC_NAME, "tx_total")))
                .hasValue(T - 3 * DAY);
    }

    @Test
    void theListenerHearsWritesReadsAndDuplicates() {
        int[] seen = new int[3];
        TinyTsdb heard = builder().listener(new TsdbListener() {
            @Override
            public void onWrite(int increments, long nanos) {
                seen[0] += increments;
            }

            @Override
            public void onDuplicate() {
                seen[1]++;
            }

            @Override
            public void onRead(ReadKind kind, long nanos) {
                seen[2]++;
            }
        }).build();

        heard.add("tx_total", USER, T, 1);
        heard.applyOnce("k", List.of(Increment.of("tx_total", USER, T, 1)));
        heard.applyOnce("k", List.of(Increment.of("tx_total", USER, T, 1)));
        heard.read("tx_total", USER, Window.allTime());
        heard.query("sum(tx_total)");

        assertThat(seen).containsExactly(2, 1, 2);
    }

    @Test
    void theConfigurationIsChecked() {
        assertThatThrownBy(() -> TinyTsdb.builder().tier(Grain.MINUTE, Duration.ofHours(1))
                .tier(Grain.MINUTE, Duration.ofHours(2)).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TinyTsdb.builder().tier(Grain.ALL, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.read("tx_total", USER, Window.rolling(5, Grain.SECOND)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No SECOND tier");
        assertThatThrownBy(() -> db.read("tx_total", USER, Window.rolling(Duration.ofDays(3), Grain.MINUTE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> Window.rolling(Duration.ofSeconds(90), Grain.MINUTE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.add("tx_total", USER, T, Long.MIN_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultTiers() {
        assertThat(TinyTsdb.builder().build().tiers()).extracting(TinyTsdb.Tier::grain)
                .containsExactly(Grain.MINUTE, Grain.HOUR, Grain.DAY);
    }
}
