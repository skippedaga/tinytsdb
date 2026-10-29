package io.github.skippedaga.tinytsdb;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An immutable set of label name/value pairs, sorted by name. The metric name is the label {@value #METRIC_NAME}.
 *
 * <p>As in Prometheus, a label with an empty value is the same as no label: {@link #of} drops it and {@link #get}
 * returns {@code ""} for a missing name.</p>
 */
public final class Labels {

    /** The label that holds the metric name. */
    public static final String METRIC_NAME = "__name__";

    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern METRIC = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Labels EMPTY = new Labels(new String[0], new String[0]);

    private final String[] names;
    private final String[] values;
    private final int hash;

    private Labels(String[] names, String[] values) {
        this.names = names;
        this.values = values;
        this.hash = 31 * Arrays.hashCode(names) + Arrays.hashCode(values);
    }

    public static Labels empty() {
        return EMPTY;
    }

    /**
     * @param nameValuePairs name, value, name, value…
     * @throws IllegalArgumentException on an odd count, an invalid or repeated name
     */
    public static Labels of(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Labels need name/value pairs, got " + nameValuePairs.length + " strings");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (map.put(nameValuePairs[i], nameValuePairs[i + 1]) != null) {
                throw new IllegalArgumentException("Label " + nameValuePairs[i] + " is given twice");
            }
        }
        return of(map);
    }

    /** @throws IllegalArgumentException on an invalid name */
    public static Labels of(Map<String, String> map) {
        String[] sorted = map.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toArray(String[]::new);
        String[] values = new String[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            checkName(sorted[i]);
            values[i] = map.get(sorted[i]);
        }
        int metric = Arrays.binarySearch(sorted, METRIC_NAME);
        if (metric >= 0) {
            checkMetricName(values[metric]);
        }
        return sorted.length == 0 ? EMPTY : new Labels(sorted, values);
    }

    static void checkName(String name) {
        if (name == null || (!LABEL_NAME.matcher(name).matches())) {
            throw new IllegalArgumentException("Invalid label name: " + name);
        }
    }

    static void checkMetricName(String name) {
        if (name == null || !METRIC.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid metric name: " + name);
        }
    }

    /** The value of {@code name}, or {@code ""} when there is none. */
    public String get(String name) {
        int i = Arrays.binarySearch(names, name);
        return i < 0 ? "" : values[i];
    }

    public boolean has(String name) {
        return Arrays.binarySearch(names, name) >= 0;
    }

    /** The metric name, or {@code ""}. */
    public String metricName() {
        return get(METRIC_NAME);
    }

    public int size() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    public String value(int index) {
        return values[index];
    }

    /** These labels with {@code name} set to {@code value}; an empty value removes it. */
    public Labels with(String name, String value) {
        Map<String, String> map = toMap();
        map.put(name, value);
        return of(map);
    }

    /** These labels without the given names. */
    public Labels without(Collection<String> drop) {
        Map<String, String> map = toMap();
        map.keySet().removeAll(drop);
        return map.size() == names.length ? this : of(map);
    }

    /** These labels without the metric name. */
    public Labels withoutMetricName() {
        return has(METRIC_NAME) ? without(List.of(METRIC_NAME)) : this;
    }

    /** Only the given names. */
    public Labels keep(Collection<String> keep) {
        Map<String, String> map = toMap();
        map.keySet().retainAll(keep);
        return map.size() == names.length ? this : of(map);
    }

    /** Name → value, sorted by name. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], values[i]);
        }
        return map;
    }

    /** Same labels with the strings replaced by the given ones; used to share strings between series. */
    Labels withStrings(String[] newNames, String[] newValues) {
        return new Labels(newNames, newValues);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Labels other) || hash != other.hash) {
            return false;
        }
        return Arrays.equals(names, other.names) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** PromQL style: {@code metric{a="1", b="2"}}. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(metricName()).append('{');
        boolean first = true;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(METRIC_NAME)) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(names[i]).append("=\"")
                    .append(values[i].replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
                    .append('"');
        }
        return out.append('}').toString();
    }
}
