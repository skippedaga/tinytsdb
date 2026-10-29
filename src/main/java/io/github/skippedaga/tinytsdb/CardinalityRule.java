package io.github.skippedaga.tinytsdb;

import java.time.Duration;

/**
 * Keeps a high-cardinality label (a card, a user) from growing the store without bound.
 *
 * @param label         the label it watches
 * @param maxValues     at most this many distinct values; a new value beyond it is written as {@code overflowValue},
 *                      so counts are kept but not attributed. 0: no cap
 * @param overflowValue the value written instead
 * @param idleTtl       series with this label and no write for this long are removed whole, totals included
 *                      ({@link TinyTsdb#maintain}); null: never
 */
public record CardinalityRule(String label, int maxValues, String overflowValue, Duration idleTtl) {

    public static final String DEFAULT_OVERFLOW = "__overflow__";

    public CardinalityRule {
        Labels.checkName(label);
        if (maxValues < 0) {
            throw new IllegalArgumentException("maxValues must not be negative");
        }
        if (overflowValue == null || overflowValue.isEmpty()) {
            throw new IllegalArgumentException("overflowValue must not be empty");
        }
    }

    public static CardinalityRule cap(String label, int maxValues) {
        return new CardinalityRule(label, maxValues, DEFAULT_OVERFLOW, null);
    }

    public static CardinalityRule idle(String label, Duration ttl) {
        return new CardinalityRule(label, 0, DEFAULT_OVERFLOW, ttl);
    }

    public CardinalityRule withIdleTtl(Duration ttl) {
        return new CardinalityRule(label, maxValues, overflowValue, ttl);
    }
}
