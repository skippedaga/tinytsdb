package io.github.skippedaga.tinytsdb;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A store with only the core of the SPI — no range scans, no index, no maintenance, no export — the shape of a
 * remote key-value store. Counts round trips, can be atomic per series, and can fail after a number of series.
 */
class CoreOnlyStore implements CounterStore {

    final MemoryCounterStore inner = new MemoryCounterStore();
    final AtomicInteger readManyCalls = new AtomicInteger();
    final AtomicInteger keysRead = new AtomicInteger();
    private final Atomicity atomicity;
    /** With {@link Atomicity#SERIES}: apply this many series of the next batch, then throw. -1: never. */
    volatile int failAfterSeries = -1;

    CoreOnlyStore(Atomicity atomicity) {
        this.atomicity = atomicity;
    }

    @Override
    public Atomicity atomicity() {
        return atomicity;
    }

    @Override
    public Result apply(Batch batch) {
        if (atomicity == Atomicity.BATCH) {
            return inner.apply(batch);
        }
        int applied = 0;
        int skipped = 0;
        int n = 0;
        for (SeriesBatch s : batch.series()) {
            if (failAfterSeries >= 0 && n == failAfterSeries) {
                failAfterSeries = -1;
                throw new IllegalStateException("connection lost after " + n + " series");
            }
            Result r = inner.apply(new Batch(java.util.List.of(s), null, 0));
            applied += r.applied();
            skipped += r.skipped();
            n++;
        }
        return new Result(false, applied, skipped);
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
    public Map<Labels, Map<BucketKey, Parts>> readMany(Collection<Labels> series, Collection<BucketKey> keys) {
        readManyCalls.incrementAndGet();
        keysRead.addAndGet(keys.size());
        return inner.readMany(series, keys);
    }

    @Override
    public OptionalLong firstAdded(Labels series) {
        return inner.firstAdded(series);
    }

    @Override
    public long version(Labels series) {
        return inner.version(series);
    }

    @Override
    public RingSnapshot snapshot(Labels series, String key) {
        return inner.snapshot(series, key);
    }

    @Override
    public boolean putSnapshot(Labels series, String key, RingSnapshot snapshot) {
        return inner.putSnapshot(series, key, snapshot);
    }
}
