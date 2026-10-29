package io.github.skippedaga.tinytsdb;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * For stores that keep several series in one key: splits a series' labels into the labels of its storage key and
 * the labels that name a field inside it. With fields {@code __name__, currency, status}, the series
 * {@code amount_total{card="c1", currency="EUR", status="OK"}} lives in key {@code {card="c1"}}, field
 * {@code amount_total|EUR|OK}, and every metric, currency and status of the card is read with one call.
 *
 * @param key   the labels that are not fields
 * @param field the field labels' values in {@code fieldLabels} order, joined with {@code |} (missing: empty)
 */
public record StorageKey(Labels key, String field) {

    /** @param fieldLabels the labels that become the field, in a fixed order */
    public static StorageKey of(Labels series, java.util.List<String> fieldLabels) {
        Set<String> fields = Set.copyOf(fieldLabels);
        Map<String, String> key = new TreeMap<>();
        for (int i = 0; i < series.size(); i++) {
            if (!fields.contains(series.name(i))) {
                key.put(series.name(i), series.value(i));
            }
        }
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < fieldLabels.size(); i++) {
            if (i > 0) {
                field.append('|');
            }
            field.append(series.get(fieldLabels.get(i)).replace("|", "\\|"));
        }
        return new StorageKey(Labels.of(key), field.toString());
    }
}
