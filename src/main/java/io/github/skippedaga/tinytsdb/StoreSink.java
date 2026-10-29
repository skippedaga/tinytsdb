package io.github.skippedaga.tinytsdb;

/** Receives the content of a store, one item at a time ({@link CounterStore#export}). */
public interface StoreSink {

    void bucket(Labels series, Grain grain, long start, Parts parts);

    /** The series' earliest event time with a positive delta. */
    void firstAdded(Labels series, long occurredAtMs);

    void claim(String key, long expiresAtMs);
}
