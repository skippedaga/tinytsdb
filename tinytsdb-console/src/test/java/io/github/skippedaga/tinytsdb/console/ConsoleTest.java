package io.github.skippedaga.tinytsdb.console;

import com.sun.net.httpserver.HttpServer;
import io.github.skippedaga.tinytsdb.CounterStore;
import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.MemoryCounterStore;
import io.github.skippedaga.tinytsdb.TinyTsdb;
import io.github.skippedaga.tinytsdb.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleTest {

    static final Instant NOW = Instant.parse("2026-10-29T12:00:30Z");
    static final long T = NOW.toEpochMilli();
    static final long MIN = 60_000;
    static final long DAY = 86_400_000;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ConsoleMetrics metrics = new ConsoleMetrics();
    private HttpServer server;

    private TinyTsdb db(CounterStore store) {
        return TinyTsdb.builder().clock(clock).zone(ZoneId.of("Europe/Kyiv")).store(store).listener(metrics)
                .tier(Grain.MINUTE, Duration.ofHours(26))
                .tier(Grain.HOUR, Duration.ofDays(32))
                .tier(Grain.DAY, Duration.ofDays(400))
                .build();
    }

    private static ConsoleConfig config() {
        return ConsoleConfig.builder()
                .title("Card counters")
                .metric(ConsoleConfig.Metric.count("n_total", "Payments"))
                .metric(ConsoleConfig.Metric.amount("amount_total", "Amount", "currency"))
                .seriesLabel("card")
                .seriesLabel("currency")
                .browseLabel("merchant")
                .anchored("first7d", "First 7 days", "n_ok_total", Duration.ofDays(7))
                .text("show", "Apply")
                .build();
    }

    private ConsoleApi filled(CounterStore store) {
        TinyTsdb db = db(store);
        Labels card = Labels.of("card", "c1", "currency", "EUR", "merchant", "m1");
        db.add("n_ok_total", card, T - 2 * DAY, 1);
        db.add("n_total", card, T - 2 * DAY, 1);
        db.add("n_total", card, T - 3 * MIN, 2);
        db.add("amount_total", card, T - 3 * MIN, 12_50);
        db.add("amount_total", card, T - 2 * MIN, -2_50);
        db.add("n_total", Labels.of("card", "c2", "currency", "EUR", "merchant", "m2"), T - MIN, 5);
        return new ConsoleApi(db, config(), metrics);
    }

    private static ConsoleApi.Response get(ConsoleApi api, String path, Map<String, String> q) {
        return api.handle("GET", path, q);
    }

    @Test
    void describeListsWhatThePageShows() {
        ConsoleApi.Response r = get(filled(new MemoryCounterStore()), "describe", Map.of());

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(r.body())
                .contains("\"title\":\"Card counters\"", "\"zone\":\"Europe/Kyiv\"",
                        "{\"name\":\"amount_total\",\"title\":\"Amount\",\"unit\":\"MINOR_UNITS\",\"currencyLabel\":\"currency\"}",
                        "\"seriesLabels\":[\"card\",\"currency\"]", "\"browseLabels\":[\"merchant\"]",
                        "{\"key\":\"5m\",\"title\":\"5 min\",\"kind\":\"rolling\"}",
                        "{\"key\":\"first7d\",\"title\":\"First 7 days\",\"kind\":\"anchored\"}",
                        "\"text\":{\"show\":\"Apply\"}");
    }

    @Test
    void queryGivesEveryMetricOverEveryWindowWithItsParts() {
        ConsoleApi.Response r = get(filled(new MemoryCounterStore()), "query",
                Map.of("card", "c1", "currency", "EUR", "metric", "amount_total", "window", "5m,all,d:2026-10-29"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body())
                .contains("\"series\":{\"card\":\"c1\",\"currency\":\"EUR\"}")
                .contains("{\"net\":1000,\"added\":1250,\"retracted\":250,\"from\":\"2026-10-29T11:56:00Z\","
                        + "\"to\":\"2026-10-29T12:01:00Z\",\"exact\":true,\"source\":\"BUCKETS\","
                        + "\"metric\":\"amount_total\",\"window\":\"5m\",\"title\":\"5 min\"}")
                .contains("\"window\":\"all\"")
                .contains("\"window\":\"d:2026-10-29\"");
    }

    @Test
    void theOverviewHasTheAnchoredWindowTheCurrentBucketsAndAllTime() {
        String body = get(filled(new MemoryCounterStore()), "overview",
                Map.of("card", "c1", "currency", "EUR", "metric", "n_total")).body();

        assertThat(body)
                .contains("\"key\":\"first7d\"", "\"opened\":true", "\"open\":true")
                .contains("\"id\":\"m:2026-10-29T12:00\"", "\"id\":\"h:2026-10-29T12\"", "\"id\":\"d:2026-10-29\"",
                        "\"id\":\"w:2026-W44\"", "\"id\":\"mo:2026-10\"", "\"id\":\"y:2026\"")
                .contains("\"firstAt\":\"2026-10-27T12:00:30Z\"");
        assertThat(body).contains("\"all\":{\"net\":3,");
    }

    @Test
    void rollingRowsSayWhereTheirValueCameFrom() {
        String body = get(filled(new MemoryCounterStore()), "rolling",
                Map.of("card", "c1", "currency", "EUR", "metric", "n_total")).body();

        assertThat(body).contains("\"key\":\"5m\"", "\"steps\":5", "\"grain\":\"m\"", "\"key\":\"30d\"",
                "\"grain\":\"h\"", "\"source\":\"BUCKETS\"");
    }

    @Test
    void historyHasOneBarPerBucket() {
        String body = get(filled(new MemoryCounterStore()), "history",
                Map.of("card", "c1", "currency", "EUR", "metric", "n_total", "grain", "d", "count", "7")).body();

        assertThat(body).contains("\"grain\":\"d\"", "\"id\":\"d:2026-10-23\"", "\"id\":\"d:2026-10-29\"");
        assertThat(body.split("\"id\":").length - 1).isEqualTo(7);
    }

    @Test
    void labelsListTheTopValuesTodayAndAllTime() {
        ConsoleApi api = filled(new MemoryCounterStore());

        String body = get(api, "labels", Map.of("label", "merchant", "metric", "n_total", "top", "5")).body();
        assertThat(body).contains("\"values\":2",
                "{\"value\":\"m2\",\"today\":5,\"all\":5,\"allRetracted\":0}",
                "{\"value\":\"m1\",\"today\":2,\"all\":3,\"allRetracted\":0}");
        assertThat(body.indexOf("m2")).isLessThan(body.indexOf("m1"));
        assertThat(get(api, "labels", Map.of("label", "card")).status()).isEqualTo(400);
    }

    @Test
    void promqlAnswersInPrometheusShape() {
        ConsoleApi api = filled(new MemoryCounterStore());

        assertThat(get(api, "promql", Map.of("query", "sum(increase(n_total[5m]))")).body())
                .isEqualTo("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":"
                        + "[{\"metric\":{},\"value\":[1793275230,\"7\"]}]}}");
        ConsoleApi.Response bad = get(api, "promql", Map.of("query", "rate(x[5m])"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("\"errorType\":\"bad_data\"");
    }

    @Test
    void statusShowsTheStoreAndTheLatencies() {
        ConsoleApi api = filled(new MemoryCounterStore());
        get(api, "query", Map.of("card", "c1"));

        assertThat(get(api, "status", Map.of()).body())
                .contains("\"type\":\"MemoryCounterStore\"", "\"atomicity\":\"BATCH\"",
                        "\"capabilities\":[\"range\",\"index\",\"maintenance\",\"export\"]", "\"series\":4")
                .contains("\"writes\":{\"count\":6,").contains("\"increments\":6");
    }

    @Test
    void errorsAreSaidPlainly() {
        ConsoleApi api = filled(new MemoryCounterStore());

        assertThat(api.handle("POST", "query", Map.of()).status()).isEqualTo(405);
        assertThat(get(api, "nothing", Map.of()).status()).isEqualTo(404);
        ConsoleApi.Response unknown = get(api, "query", Map.of("card", "c1", "metric", "secret_total"));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body()).isEqualTo("{\"error\":\"Unknown metric secret_total\"}");
        assertThat(get(api, "query", Map.of("card", "c1", "window", "forever")).status()).isEqualTo(400);
        assertThat(get(api, "history", Map.of("card", "c1", "grain", "all")).status()).isEqualTo(400);
    }

    @Test
    void withoutAnIndexTheLabelPanelIsOff() {
        ConsoleApi api = new ConsoleApi(db(new CoreOnly()), config(), null);

        assertThat(get(api, "describe", Map.of()).body()).contains("\"browseLabels\":[]");
        assertThat(get(api, "labels", Map.of("label", "merchant")).status()).isEqualTo(501);
        assertThat(get(api, "status", Map.of()).body()).contains("\"capabilities\":[]", "\"metrics\":null");
    }

    @Test
    void jsonEscapesWhatCouldBreakOutOfAPage() {
        assertThat(Json.write(Map.of("v", "</script><b>\"x\"\n&")))
                .isEqualTo("{\"v\":\"\\u003c/script\\u003e\\u003cb\\u003e\\\"x\\\"\\n\\u0026\"}");
        assertThat(Json.write(List.of(1.0, 1.5, Double.NaN, true))).isEqualTo("[1,1.5,null,true]");
    }

    // ---- over HTTP -------------------------------------------------------------------------------------------------

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpResponse<String> http(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theHandlerServesThePageItsAssetsAndTheApiWithStrictHeaders() throws Exception {
        server = ConsoleHttpHandler.serve(filled(new MemoryCounterStore()), new InetSocketAddress("127.0.0.1", 0),
                "/tinytsdb", exchange -> true);

        HttpResponse<String> page = http("/tinytsdb/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValue(ConsoleHttpHandler.CSP);
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(page.body()).contains("<script src=\"assets/console.js\" defer></script>")
                .doesNotContain("<script>").doesNotContain("style=");

        assertThat(http("/tinytsdb").statusCode()).isEqualTo(308);
        assertThat(http("/tinytsdb/assets/console.js").headers().firstValue("Content-Type"))
                .hasValue("text/javascript; charset=utf-8");
        assertThat(http("/tinytsdb/assets/console.css").statusCode()).isEqualTo(200);
        assertThat(http("/tinytsdb/assets/../index.html").statusCode()).isEqualTo(404);
        assertThat(http("/tinytsdb/assets/%2e%2e%2findex.html").statusCode()).isEqualTo(404);

        HttpResponse<String> api = http("/tinytsdb/api/query?card=c1&currency=EUR&metric=n_total&window=all");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(api.body()).contains("\"window\":\"all\"", "\"net\":3");
    }

    @Test
    void theHandlerRefusesWhatAllowSaysNo() throws Exception {
        server = ConsoleHttpHandler.serve(filled(new MemoryCounterStore()), new InetSocketAddress("127.0.0.1", 0),
                "/tinytsdb", exchange -> false);

        assertThat(http("/tinytsdb/").statusCode()).isEqualTo(403);
        assertThat(http("/tinytsdb/api/describe").statusCode()).isEqualTo(403);
    }

    /** Only the core SPI. */
    private static final class CoreOnly implements CounterStore {
        private final MemoryCounterStore inner = new MemoryCounterStore();

        @Override
        public Result apply(Batch batch) {
            return inner.apply(batch);
        }

        @Override
        public boolean claim(String key, long expiresAtMs) {
            return inner.claim(key, expiresAtMs);
        }

        @Override
        public void releaseClaim(String key) {
            inner.releaseClaim(key);
        }

        @Override
        public Map<Labels, Map<BucketKey, io.github.skippedaga.tinytsdb.Parts>> readMany(
                java.util.Collection<Labels> series, java.util.Collection<BucketKey> keys) {
            return inner.readMany(series, keys);
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
        public io.github.skippedaga.tinytsdb.RingSnapshot snapshot(Labels series, String key) {
            return inner.snapshot(series, key);
        }

        @Override
        public boolean putSnapshot(Labels series, String key, io.github.skippedaga.tinytsdb.RingSnapshot snapshot) {
            return inner.putSnapshot(series, key, snapshot);
        }
    }

    @Test
    void windowPresetKeysAreChecked() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConsoleConfig.builder()
                        .metric(ConsoleConfig.Metric.count("n_total", "n")).window("a b", "x", Window.allTime()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
