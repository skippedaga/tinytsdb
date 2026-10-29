package io.github.skippedaga.tinytsdb;

/** Receives the content of a store, one item at a time ({@link ExportableStore#export}). */
public interface StoreSink {

    void bucket(Labels series, Grain grain, long start, Parts parts);

    /** The series' earliest event time with a positive delta. */
    void firstAdded(Labels series, long occurredAtMs);

    /** The last offset the series applied for {@code stream}. */
    default void position(Labels series, String stream, long offset) {
    }

    void claim(String key, long expiresAtMs);
}
