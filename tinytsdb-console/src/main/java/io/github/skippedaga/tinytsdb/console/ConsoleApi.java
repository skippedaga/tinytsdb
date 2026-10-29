package io.github.skippedaga.tinytsdb.console;

import io.github.skippedaga.tinytsdb.CounterStore;
import io.github.skippedaga.tinytsdb.ExportableStore;
import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.IndexedStore;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.MaintainableStore;
import io.github.skippedaga.tinytsdb.Matcher;
import io.github.skippedaga.tinytsdb.MemoryCounterStore;
import io.github.skippedaga.tinytsdb.QueryException;
import io.github.skippedaga.tinytsdb.QueryResult;
import io.github.skippedaga.tinytsdb.RangeStore;
import io.github.skippedaga.tinytsdb.Reading;
import io.github.skippedaga.tinytsdb.TinyTsdb;
import io.github.skippedaga.tinytsdb.Window;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * The console's read API, framework-free: a request (method, path, query parameters) in, a JSON answer out. Serve it
 * with {@link ConsoleHttpHandler}, or call {@link #handle} from any web framework's controller.
 *
 * <p>Read-only: there is no endpoint that writes. A series is picked by the query parameters named in
 * {@link ConsoleConfig#seriesLabels()} ({@code ?user=7}); a window parameter is a preset key, a bucket id
 * ({@code d:2026-10-29}) or {@code all}. Values are shown as stored — already transformed — so a raw identifier
 * never reaches the page.</p>
 *
 * <pre>
 * GET api/describe                                  metrics, labels, windows, tiers, wording
 * GET api/query?metric=a,b&amp;window=5m,24h&amp;user=7     every metric × window: net, added, retracted, from, to
 * GET api/overview?metric=a&amp;user=7                   anchored windows, current calendar buckets, all time
 * GET api/rolling?metric=a&amp;user=7                    every rolling preset, with where its value came from
 * GET api/history?metric=a&amp;grain=d&amp;count=30&amp;user=7  the last buckets of a grain
 * GET api/labels?label=card&amp;metric=a&amp;top=10&amp;user=7  top values of a label, today and all time (needs an index)
 * GET api/promql?query=...                          tinytsdb's PromQL subset, Prometheus' /api/v1/query shape
 * GET api/status                                    as of, store capabilities, latencies
 * </pre>
 */
public final class ConsoleApi {

    /** An answer. */
    public record Response(int status, String contentType, String body) {

        static Response json(int status, Object value) {
            return new Response(status, "application/json; charset=utf-8", Json.write(value));
        }

        static Response error(int status, String message) {
            return json(status, Map.of("error", message));
        }
    }

    private static final int MAX_HISTORY = 400;
    private static final int MAX_TOP = 200;

    private final TinyTsdb db;
    private final ConsoleConfig config;
    private final ConsoleMetrics metrics;

    /** @param metrics the listener registered with {@code db}, for the status strip; may be null */
    public ConsoleApi(TinyTsdb db, ConsoleConfig config, ConsoleMetrics metrics) {
        this.db = db;
        this.config = config;
        this.metrics = metrics;
    }

    public ConsoleConfig config() {
        return config;
    }

    /**
     * Answers one request.
     *
     * @param path  the part after the console's api prefix, e.g. {@code query}
     * @param query decoded query parameters
     */
    public Response handle(String method, String path, Map<String, String> query) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return Response.error(405, "The console is read-only");
        }
        try {
            return switch (path) {
                case "describe" -> Response.json(200, describe());
                case "query" -> Response.json(200, query(query));
                case "overview" -> Response.json(200, overview(query));
                case "rolling" -> Response.json(200, rolling(query));
                case "history" -> Response.json(200, history(query));
                case "labels" -> labelValues(query);
                case "promql" -> promql(query);
                case "status" -> Response.json(200, status());
                default -> Response.error(404, "No such endpoint: " + path);
            };
        } catch (QueryException | IllegalArgumentException e) {
            return Response.error(400, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", config.title());
        m.put("zone", db.zone().getId());
        m.put("refreshSeconds", config.refreshSeconds());
        List<Map<String, Object>> metricList = new ArrayList<>();
        for (ConsoleConfig.Metric metric : config.metrics()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", metric.name());
            x.put("title", metric.title());
            x.put("unit", metric.unit());
            x.put("currencyLabel", metric.currencyLabel());
            metricList.add(x);
        }
        m.put("metrics", metricList);
        m.put("seriesLabels", config.seriesLabels());
        m.put("browseLabels", store() instanceof IndexedStore ? config.browseLabels() : List.of());
        m.put("windows", presets(config.windows()));
        m.put("anchored", presets(config.anchored()));
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (TinyTsdb.Tier t : db.tiers()) {
            tiers.add(Map.of("grain", t.grain().prefix(), "retentionSeconds", t.retention().toSeconds()));
        }
        m.put("tiers", tiers);
        m.put("text", config.text());
        return m;
    }

    private static List<Map<String, Object>> presets(List<ConsoleConfig.WindowPreset> presets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsoleConfig.WindowPreset p : presets) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", p.key());
            x.put("title", p.title());
            x.put("kind", p.window().getClass().getSimpleName().toLowerCase(Locale.ROOT));
            out.add(x);
        }
        return out;
    }

    private Map<String, Object> query(Map<String, String> q) {
        Labels labels = labels(q);
        List<String> metricNames = metricNames(q);
        List<String> keys = list(q.getOrDefault("window", ""));
        if (keys.isEmpty()) {
            keys = config.windows().stream().map(ConsoleConfig.WindowPreset::key).toList();
        }
        List<Window> windows = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (String k : keys) {
            ConsoleConfig.WindowPreset p = preset(k);
            windows.add(p == null ? window(k) : p.window());
            titles.add(p == null ? k : p.title());
        }
        long now = db.clock().millis();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String metric : metricNames) {
            Map<Window, Reading> read = sumOver(metric, labels, windows, now);
            for (int i = 0; i < windows.size(); i++) {
                Map<String, Object> item = reading(read.get(windows.get(i)));
                item.put("metric", metric);
                item.put("window", keys.get(i));
                item.put("title", titles.get(i));
                items.add(item);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(now).toString());
        m.put("series", labels.toMap());
        m.put("items", items);
        return m;
    }

    private Map<String, Object> overview(Map<String, String> q) {
        Labels labels = labels(q);
        String metric = metric(q);
        long now = db.clock().millis();

        // one anchor for everything the labels pick: the earliest first event of the anchor metric over all of its
        // series, so "the card's first 7 days" do not become each merchant's first 7 days
        List<Map<String, Object>> anchoredOut = new ArrayList<>();
        for (ConsoleConfig.WindowPreset p : config.anchored()) {
            Window.Anchored a = (Window.Anchored) p.window();
            OptionalLong first = db.firstAdded(a.anchorMetric(), matchers(labels));
            Map<String, Object> item;
            if (first.isEmpty()) {
                item = reading(new Reading(io.github.skippedaga.tinytsdb.Parts.ZERO, now, now, now, true,
                        Reading.Source.BUCKETS));
                item.put("opened", false);
                item.put("open", false);
            } else {
                Grain finest = db.tiers().get(0).grain();
                long from = finest.floor(first.getAsLong(), db.zone());
                long to = Math.addExact(from, a.length().toMillis());
                Window range = Window.range(from, to);
                Reading r = sumOver(metric, labels, List.of(range), now).get(range);
                item = reading(r);
                item.put("opened", true);
                item.put("open", to > now);
            }
            item.put("key", p.key());
            item.put("title", p.title());
            anchoredOut.add(item);
        }

        List<String> ids = new ArrayList<>();
        for (Grain g : List.of(Grain.MINUTE, Grain.HOUR, Grain.DAY, Grain.WEEK, Grain.MONTH, Grain.YEAR)) {
            ids.add(g.id(now, db.zone()));
        }
        List<Window> calendar = new ArrayList<>(ids.stream().map(Window::calendar).toList());
        calendar.add(Window.allTime());
        Map<Window, Reading> read = sumOver(metric, labels, calendar, now);
        List<Map<String, Object>> calendarOut = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> item = reading(read.get(calendar.get(i)));
            item.put("id", ids.get(i));
            item.put("grain", ids.get(i).substring(0, ids.get(i).indexOf(':')));
            calendarOut.add(item);
        }
        Map<String, Object> all = reading(read.get(Window.allTime()));
        OptionalLong first = db.firstAdded(metric, matchers(labels));
        all.put("firstAt", first.isPresent() ? Instant.ofEpochMilli(first.getAsLong()).toString() : null);
        all.remove("from");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(now).toString());
        m.put("metric", metric);
        m.put("series", labels.toMap());
        m.put("anchored", anchoredOut);
        m.put("calendar", calendarOut);
        m.put("all", all);
        return m;
    }

    private Map<String, Object> rolling(Map<String, String> q) {
        Labels labels = labels(q);
        String metric = metric(q);
        long now = db.clock().millis();
        List<ConsoleConfig.WindowPreset> presets = config.windows().stream()
                .filter(p -> p.window() instanceof Window.Rolling).toList();
        Map<Window, Reading> read = presets.isEmpty() ? Map.of()
                : sumOver(metric, labels, presets.stream().map(ConsoleConfig.WindowPreset::window).toList(), now);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConsoleConfig.WindowPreset p : presets) {
            Window.Rolling r = (Window.Rolling) p.window();
            Map<String, Object> item = reading(read.get(p.window()));
            item.put("key", p.key());
            item.put("title", p.title());
            item.put("steps", r.steps());
            item.put("grain", r.grain().prefix());
            rows.add(item);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(now).toString());
        m.put("metric", metric);
        m.put("rows", rows);
        return m;
    }

    private Map<String, Object> history(Map<String, String> q) {
        Labels labels = labels(q);
        String metric = metric(q);
        Grain grain = grain(q.getOrDefault("grain", "d"));
        int count = Math.min(MAX_HISTORY, Math.max(1, Integer.parseInt(q.getOrDefault("count", "30"))));
        long now = db.clock().millis();
        long current = grain.floor(now, db.zone());
        List<String> ids = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            ids.add(grain.id(grain.plus(current, -i, db.zone()), db.zone()));
        }
        List<Window> windows = ids.stream().map(Window::calendar).toList();
        Map<Window, Reading> read = sumOver(metric, labels, windows, now);
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Map<String, Object> item = reading(read.get(windows.get(i)));
            item.put("id", ids.get(i));
            bars.add(item);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(now).toString());
        m.put("metric", metric);
        m.put("grain", grain.prefix());
        m.put("bars", bars);
        return m;
    }

    private Response labelValues(Map<String, String> q) {
        if (!(store() instanceof IndexedStore)) {
            return Response.error(501, "This store has no label index: values by label are not available");
        }
        String label = q.get("label");
        if (label == null || !config.browseLabels().contains(label)) {
            throw new IllegalArgumentException("label must be one of " + config.browseLabels());
        }
        String metric = metric(q);
        int top = Math.min(MAX_TOP, Math.max(1, Integer.parseInt(q.getOrDefault("top", "10"))));
        List<Matcher> filter = new ArrayList<>();
        labels(q).toMap().forEach((k, v) -> filter.add(Matcher.of(Matcher.Type.EQUAL, k, v)));
        long now = db.clock().millis();
        Window today = Window.calendar(Grain.DAY.id(now, db.zone()));
        Map<Window, Map<Labels, Reading>> read = db.readGrouped(metric, filter, List.of(label),
                List.of(today, Window.allTime()), now);
        Map<Labels, Reading> all = read.get(Window.allTime());
        List<Map<String, Object>> rows = new ArrayList<>();
        all.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Labels, Reading> e) -> e.getValue().net()).reversed()
                        .thenComparing(e -> e.getKey().get(label)))
                .limit(top)
                .forEach(e -> {
                    Reading t = read.get(today).get(e.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("value", e.getKey().get(label));
                    row.put("today", t == null ? 0 : t.net());
                    row.put("all", e.getValue().net());
                    row.put("allRetracted", e.getValue().retracted());
                    rows.add(row);
                });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(now).toString());
        m.put("metric", metric);
        m.put("label", label);
        m.put("values", all.size());
        m.put("rows", rows);
        return Response.json(200, m);
    }

    private Response promql(Map<String, String> q) {
        String query = q.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        long now = db.clock().millis();
        double at = now / 1000.0;
        QueryResult result;
        try {
            result = db.query(query, now);
        } catch (QueryException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("errorType", "bad_data");
            error.put("error", e.getMessage());
            return Response.json(400, error);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (result instanceof QueryResult.Scalar s) {
            data.put("resultType", "scalar");
            data.put("result", List.of(at, number(s.value())));
        } else {
            data.put("resultType", "vector");
            List<Map<String, Object>> samples = new ArrayList<>();
            for (QueryResult.Sample s : result.vector()) {
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("metric", s.labels().toMap());
                sample.put("value", List.of(at, number(s.value())));
                samples.add(sample);
            }
            data.put("result", samples);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("status", "success");
        answer.put("data", data);
        return Response.json(200, answer);
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", Instant.ofEpochMilli(db.clock().millis()).toString());
        Map<String, Object> s = new LinkedHashMap<>();
        CounterStore store = store();
        s.put("type", store.getClass().getSimpleName());
        s.put("atomicity", store.atomicity());
        List<String> caps = new ArrayList<>();
        if (store instanceof RangeStore) {
            caps.add("range");
        }
        if (store instanceof IndexedStore) {
            caps.add("index");
        }
        if (store instanceof MaintainableStore) {
            caps.add("maintenance");
        }
        if (store instanceof ExportableStore) {
            caps.add("export");
        }
        s.put("capabilities", caps);
        if (store instanceof MemoryCounterStore mem) {
            s.put("series", mem.seriesCount());
            s.put("buckets", mem.bucketCount());
        }
        m.put("store", s);
        m.put("metrics", metrics == null ? null : metrics.view());
        return m;
    }

    // ---------------------------------------------------------------------------------------------------------------

    private CounterStore store() {
        return db.store();
    }

    /**
     * Every series of {@code metric} with the given labels, summed per window — series may carry more labels (a
     * merchant, a status). Without an index that is the one series with exactly these labels.
     */
    private Map<Window, Reading> sumOver(String metric, Labels labels, List<Window> windows, long now) {
        Map<Window, Map<Labels, Reading>> grouped = db.readGrouped(metric, matchers(labels), List.of(), windows, now);
        Map<Window, Reading> exact = null;
        Map<Window, Reading> out = new LinkedHashMap<>();
        for (Window w : windows) {
            Reading r = grouped.get(w).get(Labels.empty());
            if (r == null) {
                if (exact == null) {
                    exact = db.read(metric, labels, windows, now); // nothing matched: zeros, with their ranges
                }
                r = exact.get(w);
            }
            out.put(w, r);
        }
        return out;
    }

    private static List<Matcher> matchers(Labels labels) {
        List<Matcher> m = new ArrayList<>();
        labels.toMap().forEach((k, v) -> m.add(Matcher.of(Matcher.Type.EQUAL, k, v)));
        return m;
    }

    private static Map<String, Object> reading(Reading r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("net", r.net());
        m.put("added", r.added());
        m.put("retracted", r.retracted());
        m.put("from", r.fromMs() == Long.MIN_VALUE ? null : Instant.ofEpochMilli(r.fromMs()).toString());
        m.put("to", Instant.ofEpochMilli(r.toMs()).toString());
        m.put("exact", r.exact());
        m.put("source", r.source());
        return m;
    }

    private static String number(double v) {
        return v == Math.rint(v) && Double.isFinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** The series labels from the parameters the config names; anything else is ignored. */
    private Labels labels(Map<String, String> q) {
        Map<String, String> m = new TreeMap<>();
        for (String l : config.seriesLabels()) {
            String v = q.get(l);
            if (v != null && !v.isBlank()) {
                m.put(l, v.trim());
            }
        }
        return Labels.of(m);
    }

    private List<String> metricNames(Map<String, String> q) {
        List<String> names = list(q.getOrDefault("metric", ""));
        if (names.isEmpty()) {
            return config.metrics().stream().map(ConsoleConfig.Metric::name).toList();
        }
        for (String n : names) {
            known(n);
        }
        return names;
    }

    private String metric(Map<String, String> q) {
        String n = q.get("metric");
        if (n == null || n.isBlank()) {
            return config.metrics().get(0).name();
        }
        return known(n.trim());
    }

    private String known(String metric) {
        for (ConsoleConfig.Metric m : config.metrics()) {
            if (m.name().equals(metric)) {
                return metric;
            }
        }
        throw new IllegalArgumentException("Unknown metric " + metric);
    }

    private ConsoleConfig.WindowPreset preset(String key) {
        for (ConsoleConfig.WindowPreset p : config.windows()) {
            if (p.key().equals(key)) {
                return p;
            }
        }
        for (ConsoleConfig.WindowPreset p : config.anchored()) {
            if (p.key().equals(key)) {
                return p;
            }
        }
        return null;
    }

    private Window window(String key) {
        if ("all".equals(key)) {
            return Window.allTime();
        }
        if (key.indexOf(':') > 0) {
            Grain.parse(key, db.zone()); // validates
            return Window.calendar(key);
        }
        throw new IllegalArgumentException("Unknown window " + key);
    }

    private static Grain grain(String prefix) {
        for (Grain g : Grain.values()) {
            if (g.prefix().equals(prefix) && g != Grain.ALL) {
                return g;
            }
        }
        throw new IllegalArgumentException("Unknown grain " + prefix);
    }

    private static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
