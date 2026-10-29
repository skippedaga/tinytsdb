package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The examples in README.md, so they keep compiling and keep giving the numbers the README shows. */
class ReadmeExamplesTest {

    private static final long NOW = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();
    private static final long MIN = 60_000;

    private static TinyTsdb db() {
        return TinyTsdb.builder()
                .clock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
                .zone(ZoneId.of("Europe/Kyiv"))
                .tier(Grain.MINUTE, Duration.ofHours(48))
                .tier(Grain.HOUR, Duration.ofDays(62))
                .tier(Grain.DAY, Duration.ofDays(3 * 365))
                .build();
    }

    @Test
    void aRefundIsARetraction() {
        TinyTsdb db = db();
        Labels card = Labels.of("card", "c1", "currency", "EUR");
        db.add("amount_total", card, NOW - 30 * MIN, 12_50);     // a payment, in minor units
        db.add("amount_total", card, NOW - 30 * MIN, -4_00);     // a partial refund, at the payment's time

        Reading month = db.read("amount_total", card, Window.calendar("mo:2026-10"));
        assertThat(month.net()).isEqualTo(8_50);
        assertThat(month.added()).isEqualTo(12_50);
        assertThat(month.retracted()).isEqualTo(4_00);
    }

    @Test
    void theCubeInCode() {
        TinyTsdb db = db();
        db.add("payments_total", Labels.of("merchant", "m-books", "card", "c1"), NOW - 5 * MIN, 1);
        db.add("payments_total", Labels.of("merchant", "m-books", "card", "c2"), NOW - 3 * MIN, 1);
        db.add("payments_total", Labels.of("merchant", "m-games", "card", "c1"), NOW - 2 * 3_600_000, 1);

        List<Window> windows = List.of(Window.rolling(Duration.ofHours(1), Grain.MINUTE),
                Window.rolling(Duration.ofDays(1), Grain.MINUTE));
        Map<Window, Map<Labels, Reading>> cube = db.readGrouped("payments_total", List.of(), List.of("merchant"),
                windows, NOW);

        Labels books = Labels.of("merchant", "m-books");
        Labels games = Labels.of("merchant", "m-games");
        assertThat(cube.get(windows.get(0)).get(books).net()).isEqualTo(2);
        assertThat(cube.get(windows.get(0)).get(games).net()).isZero();   // a row in every column, 0 here
        assertThat(cube.get(windows.get(1)).get(games).net()).isEqualTo(1);

        // the same 24-hour column in PromQL
        List<QueryResult.Sample> v = db.query("sum by (merchant) (increase(payments_total[1d]))").vector();
        assertThat(v).extracting(QueryResult.Sample::value).containsExactlyInAnyOrder(2.0, 1.0);
    }

    @Test
    void aStatusChangeIsTwoWrites() {
        TinyTsdb db = db();
        Labels user = Labels.of("user", "42");
        long at = NOW - 4 * MIN;
        db.add("tx_total", user.with("status", "PENDING"), at, 1);
        // later the payment is declined: off PENDING, onto DECLINED, both at the payment's own time
        db.write(List.of(Increment.of("tx_total", user.with("status", "PENDING"), at, -1),
                Increment.of("tx_total", user.with("status", "DECLINED"), at, 1)));

        Window last10m = Window.rolling(Duration.ofMinutes(10), Grain.MINUTE);
        assertThat(db.read("tx_total", user.with("status", "PENDING"), last10m).net()).isZero();
        assertThat(db.read("tx_total", user.with("status", "PENDING"), last10m).retracted()).isEqualTo(1);
        assertThat(db.read("tx_total", user.with("status", "DECLINED"), last10m).net()).isEqualTo(1);
    }

    @Test
    void consumingALogAtMostOnce() {
        TinyTsdb db = db();
        List<Increment> report = List.of(Increment.of("tx_total", Labels.of("user", "42"), NOW - MIN, 1));

        assertThat(db.applyAt("payments-3", 1_207, report).applied()).isEqualTo(1);
        assertThat(db.applyAt("payments-3", 1_207, report).applied()).isZero();   // a redelivery changes nothing
        assertThat(db.read("tx_total", Labels.of("user", "42"), Window.allTime()).net()).isEqualTo(1);
    }
}
