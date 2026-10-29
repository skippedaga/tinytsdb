package io.github.skippedaga.tinytsdb.console;

import io.github.skippedaga.tinytsdb.Grain;
import io.github.skippedaga.tinytsdb.Labels;
import io.github.skippedaga.tinytsdb.Window;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the console shows and how: the metrics, the labels that pick a series, the windows on offer, how values are
 * formatted, and every word on the page. The product builds it; the console reads nothing else about the product.
 *
 * @param title          page title
 * @param metrics        the counters, in display order
 * @param seriesLabels   the labels a user fills in to pick a series (e.g. {@code user}, {@code card})
 * @param browseLabels   labels whose top values the "by label" panel lists
 * @param windows        rolling and calendar windows the explorer offers, in display order
 * @param anchored       anchored windows for the overview's "since it began" block
 * @param refreshSeconds how often the page refreshes; 0 for never
 * @param text           every word on the page, by key; missing keys fall back to English
 */
public record ConsoleConfig(String title, List<Metric> metrics, List<String> seriesLabels, List<String> browseLabels,
                            List<WindowPreset> windows, List<WindowPreset> anchored, int refreshSeconds,
                            Map<String, String> text) {

    public ConsoleConfig {
        metrics = List.copyOf(metrics);
        seriesLabels = List.copyOf(seriesLabels);
        browseLabels = List.copyOf(browseLabels);
        windows = List.copyOf(windows);
        anchored = List.copyOf(anchored);
        text = Map.copyOf(text);
    }

    /** How a metric's values read. */
    public enum Unit {
        /** Plain counts. */
        COUNT,
        /** Amounts in minor units, shown with two decimals and the value of {@link Metric#currencyLabel()}. */
        MINOR_UNITS
    }

    /**
     * @param name          the metric name in the store
     * @param title         what the page calls it
     * @param unit          how values read
     * @param currencyLabel for {@link Unit#MINOR_UNITS}: the label holding the currency, or null
     */
    public record Metric(String name, String title, Unit unit, String currencyLabel) {

        public Metric {
            Labels.of(Labels.METRIC_NAME, name); // validates
        }

        public static Metric count(String name, String title) {
            return new Metric(name, title, Unit.COUNT, null);
        }

        public static Metric amount(String name, String title, String currencyLabel) {
            return new Metric(name, title, Unit.MINOR_UNITS, currencyLabel);
        }
    }

    /** A window the page offers, under a short key used in URLs. */
    public record WindowPreset(String key, String title, Window window) {

        public WindowPreset {
            if (!key.matches("[A-Za-z0-9_.:-]{1,40}")) {
                throw new IllegalArgumentException("Window key must be 1-40 of A-Z a-z 0-9 _ . : -, got " + key);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String title = "tinytsdb";
        private final List<Metric> metrics = new ArrayList<>();
        private final List<String> seriesLabels = new ArrayList<>();
        private final List<String> browseLabels = new ArrayList<>();
        private final List<WindowPreset> windows = new ArrayList<>();
        private final List<WindowPreset> anchored = new ArrayList<>();
        private int refreshSeconds = 30;
        private final Map<String, String> text = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder metric(Metric metric) {
            metrics.add(metric);
            return this;
        }

        public Builder seriesLabel(String label) {
            seriesLabels.add(label);
            return this;
        }

        public Builder browseLabel(String label) {
            browseLabels.add(label);
            return this;
        }

        public Builder window(String key, String title, Window window) {
            windows.add(new WindowPreset(key, title, window));
            return this;
        }

        public Builder anchored(String key, String title, String anchorMetric, Duration length) {
            anchored.add(new WindowPreset(key, title, Window.anchored(anchorMetric, length)));
            return this;
        }

        public Builder refreshSeconds(int seconds) {
            this.refreshSeconds = seconds;
            return this;
        }

        /** Replaces one word or phrase on the page, e.g. {@code text("owner", "Merchant")}. */
        public Builder text(String key, String value) {
            text.put(key, value);
            return this;
        }

        /** Default windows when none were given: 5m, 1h, 24h at minute steps, 7d and 30d at hour steps. */
        public ConsoleConfig build() {
            if (windows.isEmpty()) {
                window("5m", "5 min", Window.rolling(5, Grain.MINUTE));
                window("1h", "1 hour", Window.rolling(60, Grain.MINUTE));
                window("24h", "24 hours", Window.rolling(1440, Grain.MINUTE));
                window("7d", "7 days", Window.rolling(168, Grain.HOUR));
                window("30d", "30 days", Window.rolling(720, Grain.HOUR));
            }
            if (metrics.isEmpty()) {
                throw new IllegalArgumentException("The console needs at least one metric");
            }
            return new ConsoleConfig(title, metrics, seriesLabels, browseLabels, windows, anchored, refreshSeconds,
                    text);
        }
    }
}
