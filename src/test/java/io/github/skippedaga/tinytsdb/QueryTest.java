package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.QueryResult.Sample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The PromQL subset: what parses, what it means, what is refused. */
class QueryTest {

    static final long MIN = TinyTsdbTest.MIN;
    static final long NOW = TinyTsdbTest.T + 10 * MIN;

    private final TestClock clock = new TestClock(NOW);
    private final TinyTsdb db = TinyTsdb.builder().clock(clock).build();

    /**
     * entered: user 1 DEPOSIT PENDING ×3 (one 20 minutes ago), FAILED ×1; user 1 WITHDRAWAL PENDING ×1;
     * user 2 DEPOSIT PENDING ×2. left: user 1 DEPOSIT PENDING ×1.
     */
    @BeforeEach
    void fill() {
        entered("1", "DEPOSIT", "PENDING", NOW - 20 * MIN);
        entered("1", "DEPOSIT", "PENDING", NOW - 4 * MIN);
        entered("1", "DEPOSIT", "PENDING", NOW - 3 * MIN);
        entered("1", "DEPOSIT", "FAILED", NOW - 2 * MIN);
        left("1", "DEPOSIT", "PENDING", NOW - 2 * MIN);
        entered("1", "WITHDRAWAL", "PENDING", NOW - MIN);
        entered("2", "DEPOSIT", "PENDING", NOW - MIN);
        entered("2", "DEPOSIT", "PENDING", NOW - MIN);
    }

    private void entered(String user, String direction, String status, long at) {
        db.add("tx_entered_total", Labels.of("user", user, "direction", direction, "status", status), at, 1);
    }

    private void left(String user, String direction, String status, long at) {
        db.add("tx_left_total", Labels.of("user", user, "direction", direction, "status", status), at, 1);
    }

    private Map<String, Double> run(String query) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Sample s : db.query(query, NOW).vector()) {
            out.put(s.labels().toString(), s.value());
        }
        return out;
    }

    @Test
    void anInstantSelectorIsTheTotal() {
        assertThat(run("tx_entered_total{user=\"1\", direction=\"DEPOSIT\"}")).containsExactly(
                Map.entry("tx_entered_total{direction=\"DEPOSIT\", status=\"FAILED\", user=\"1\"}", 1.0),
                Map.entry("tx_entered_total{direction=\"DEPOSIT\", status=\"PENDING\", user=\"1\"}", 3.0));
    }

    @Test
    void increaseIsTheLastMinutesIncludingTheOneInProgressAndDropsTheMetricName() {
        // [5m] at minute precision: the minutes NOW-4m .. NOW
        assertThat(run("increase(tx_entered_total{user=\"1\",status=\"PENDING\",direction=\"DEPOSIT\"}[5m])"))
                .containsExactly(Map.entry("{direction=\"DEPOSIT\", status=\"PENDING\", user=\"1\"}", 2.0));
        assertThat(run("increase(tx_entered_total{user=\"1\",status=\"PENDING\",direction=\"DEPOSIT\"}[4m])"))
                .containsExactly(Map.entry("{direction=\"DEPOSIT\", status=\"PENDING\", user=\"1\"}", 1.0));
    }

    @Test
    void addedAndRetractedReadThePartsApart() {
        db.add("amount_total", Labels.of("user", "1"), NOW, 500);
        db.add("amount_total", Labels.of("user", "1"), NOW, -120);

        assertThat(run("increase(amount_total[1h])")).containsExactly(Map.entry("{user=\"1\"}", 380.0));
        assertThat(run("added(amount_total[1h])")).containsExactly(Map.entry("{user=\"1\"}", 500.0));
        assertThat(run("retracted(amount_total[1h])")).containsExactly(Map.entry("{user=\"1\"}", 120.0));
        assertThat(run("amount_total")).containsExactly(Map.entry("amount_total{user=\"1\"}", 380.0));
    }

    @Test
    void aRangeNoTierCanServeIsRefused() {
        assertThatThrownBy(() -> run("increase(tx_entered_total[90s])"))
                .isInstanceOf(QueryException.class).hasMessageContaining("divides");
        assertThatThrownBy(() -> run("increase(tx_entered_total[365d])"))
                .isInstanceOf(QueryException.class);
    }

    @Test
    void sumBy() {
        assertThat(run("sum by (status) (increase(tx_entered_total{user=\"1\"}[5m]))")).containsExactly(
                Map.entry("{status=\"FAILED\"}", 1.0), Map.entry("{status=\"PENDING\"}", 3.0));
        // the grouping may come after the argument, and keywords are case-insensitive
        assertThat(run("SUM(increase(tx_entered_total{user=\"1\"}[5m])) BY (status)"))
                .isEqualTo(run("sum by (status) (increase(tx_entered_total{user=\"1\"}[5m]))"));
    }

    @Test
    void sumWithoutAndPlainSum() {
        assertThat(run("sum without (status, direction) (tx_entered_total)")).containsExactly(
                Map.entry("{user=\"1\"}", 5.0), Map.entry("{user=\"2\"}", 2.0));
        assertThat(run("sum(tx_entered_total)")).containsExactly(Map.entry("{}", 7.0));
    }

    @Test
    void countMinMaxAvg() {
        assertThat(run("count(tx_entered_total)")).containsExactly(Map.entry("{}", 4.0));
        assertThat(run("min(tx_entered_total)")).containsExactly(Map.entry("{}", 1.0));
        assertThat(run("max(tx_entered_total)")).containsExactly(Map.entry("{}", 3.0));
        assertThat(run("avg(tx_entered_total)")).containsExactly(Map.entry("{}", 7.0 / 4));
    }

    @Test
    void pendingNowIsEnteredMinusLeftMatchedOnTheirLabels() {
        String pendingNow = "sum by (user, direction) (tx_entered_total{status=\"PENDING\"})"
                + " - sum by (user, direction) (tx_left_total{status=\"PENDING\"})";

        // one-to-one: only label sets present on both sides
        assertThat(run(pendingNow)).containsExactly(Map.entry("{direction=\"DEPOSIT\", user=\"1\"}", 2.0));
        // or fills in the ones nothing has left yet
        assertThat(run("(" + pendingNow + ") or sum by (user, direction) (tx_entered_total{status=\"PENDING\"})"))
                .containsExactly(
                        Map.entry("{direction=\"DEPOSIT\", user=\"1\"}", 2.0),
                        Map.entry("{direction=\"DEPOSIT\", user=\"2\"}", 2.0),
                        Map.entry("{direction=\"WITHDRAWAL\", user=\"1\"}", 1.0));
    }

    @Test
    void onAndIgnoring() {
        assertThat(run("tx_entered_total{user=\"1\",direction=\"DEPOSIT\",status=\"PENDING\"}"
                + " - on (user, direction) tx_left_total"))
                .containsExactly(Map.entry("{direction=\"DEPOSIT\", user=\"1\"}", 2.0));
        assertThat(run("tx_entered_total{user=\"1\",direction=\"DEPOSIT\",status=\"PENDING\"}"
                + " - ignoring (status) tx_left_total"))
                .containsExactly(Map.entry("{direction=\"DEPOSIT\", user=\"1\"}", 2.0));
    }

    @Test
    void manyToManyIsRefused() {
        assertThatThrownBy(() -> run("tx_entered_total - on (user) tx_left_total"))
                .isInstanceOf(QueryException.class).hasMessageContaining("many-to-one");
        assertThatThrownBy(() -> run("tx_left_total - on (user) tx_entered_total"))
                .isInstanceOf(QueryException.class).hasMessageContaining("right-hand side");
    }

    @Test
    void comparisonsFilterOrWithBoolReturnZeroOrOne() {
        assertThat(run("tx_entered_total > 1")).containsExactly(
                Map.entry("tx_entered_total{direction=\"DEPOSIT\", status=\"PENDING\", user=\"1\"}", 3.0),
                Map.entry("tx_entered_total{direction=\"DEPOSIT\", status=\"PENDING\", user=\"2\"}", 2.0));
        assertThat(run("sum by (user) (tx_entered_total) >= bool 3")).containsExactly(
                Map.entry("{user=\"1\"}", 1.0), Map.entry("{user=\"2\"}", 0.0));
        assertThat(run("2 < tx_entered_total")).hasSize(1);
    }

    @Test
    void andUnless() {
        assertThat(run("sum by (user) (tx_entered_total) and sum by (user) (tx_left_total)"))
                .containsOnlyKeys("{user=\"1\"}");
        assertThat(run("sum by (user) (tx_entered_total) unless sum by (user) (tx_left_total)"))
                .containsOnlyKeys("{user=\"2\"}");
    }

    @Test
    void arithmeticWithScalars() {
        assertThat(run("sum(tx_entered_total) * 2 + 1")).containsExactly(Map.entry("{}", 15.0));
        assertThat(run("-sum(tx_entered_total)")).containsExactly(Map.entry("{}", -7.0));
        assertThat(db.query("1 + 2 * 3", NOW).scalar()).isEqualTo(7);
        assertThat(db.query("(1 + 2) * 3", NOW).scalar()).isEqualTo(9);
        assertThat(db.query("2 > bool 1", NOW).scalar()).isEqualTo(1);
        assertThat(db.query("-1.5e1", NOW).scalar()).isEqualTo(-15);
    }

    @Test
    void regexAndNegativeMatchers() {
        assertThat(run("sum(tx_entered_total{status=~\"PEND.*\"})")).containsExactly(Map.entry("{}", 6.0));
        assertThat(run("sum(tx_entered_total{status!~\"PEND.*\"})")).containsExactly(Map.entry("{}", 1.0));
        assertThat(run("sum(tx_entered_total{user!=\"1\"})")).containsExactly(Map.entry("{}", 2.0));
        // anchored at both ends, as in PromQL
        assertThat(run("sum(tx_entered_total{status=~\"PEND\"})")).isEmpty();
        assertThat(run("sum({__name__=~\"tx_.*\", user=\"1\"})")).containsExactly(Map.entry("{}", 6.0));
    }

    @Test
    void noMatchIsAnEmptyVector() {
        assertThat(run("increase(tx_entered_total{user=\"404\"}[5m])")).isEmpty();
        assertThat(run("sum(nothing_total)")).isEmpty();
    }

    @Test
    void aPreparedQueryRunsAgain() {
        Query q = db.prepare("sum(increase(tx_entered_total{user=\"2\"}[5m]))");
        assertThat(q.execute(NOW).vector().get(0).value()).isEqualTo(2);

        entered("2", "DEPOSIT", "PENDING", NOW);
        assertThat(q.execute(NOW).vector().get(0).value()).isEqualTo(3);
        clock.advance(10 * MIN);
        assertThat(q.execute(clock.millis()).vector()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"500ms,500", "1s,1000", "5m,300000", "1h30m,5400000", "2d,172800000", "1w,604800000",
            "1y,31536000000"})
    void durations(String text, long ms) {
        assertThat(Lexer.parseDuration(text)).isEqualTo(ms);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ",
            "rate(x[5m])",                 // unsupported function
            "x[5m]",                       // range outside increase
            "increase(x)",                 // no range
            "increase(x[5q])",             // bad unit
            "increase(x[30m1h])",          // units out of order
            "x offset 5m",
            "sum(x) by",
            "x{a=1}",                      // value must be quoted
            "x{a~\"1\"}",
            "{a=\"\"}",                    // only matches the empty string
            "x + on(a) group_left x",
            "topk(3, x)",
            "1 > 2",                       // scalar comparison needs bool
            "x and 1",
            "sum(1)",
            "(x)[5m:1m]",
            "x @ 100",
            "\"unterminated",
            "x +",
            "x y"})
    void unsupportedOrInvalidQueriesAreRefused(String query) {
        assertThatThrownBy(() -> db.query(query, NOW)).isInstanceOf(QueryException.class);
    }

    @Test
    void stringEscapes() {
        db.add("q_total", Labels.of("path", "a\"b\\c"), NOW, 1);
        assertThat(run("q_total{path=\"a\\\"b\\\\c\"}")).hasSize(1);
        assertThat(run("q_total{path='a\"b\\\\c'}")).hasSize(1);
        // an unknown escape keeps its backslash, for regular expressions
        db.add("q_total", Labels.of("path", "x7"), NOW, 1);
        assertThat(run("q_total{path=~\"x\\d\"}")).hasSize(1);
    }

    @Test
    void theResultIsSortedByLabels() {
        List<String> labels = db.query("tx_entered_total", NOW).vector().stream()
                .map(s -> s.labels().toString()).toList();

        assertThat(labels).isSorted();
    }
}
