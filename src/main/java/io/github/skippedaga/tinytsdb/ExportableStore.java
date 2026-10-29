package io.github.skippedaga.tinytsdb;

/** A store that can hand over its content and merge another's: backups, moves, a hot store rebuilt from a cold one. */
public interface ExportableStore extends CounterStore {

    /** Hands every bucket, anchor, position and claim to {@code sink}. */
    void export(StoreSink sink);

    /** A sink that merges what it is given into this store: max per part and offset, min per anchor, union of claims. */
    StoreSink mergeMaxSink();
}
