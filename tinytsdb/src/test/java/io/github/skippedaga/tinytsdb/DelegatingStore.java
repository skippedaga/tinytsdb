package io.github.skippedaga.tinytsdb;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;

/** Passes everything through to a {@link MemoryCounterStore}, with every capability; tests override what they need. */
class DelegatingStore implements RangeStore, IndexedStore, MaintainableStore, ExportableStore {

    final MemoryCounterStore inner;

    DelegatingStore(MemoryCounterStore inner) {
        this.inner = inner;
    }

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
    public Map<Labels, Map<BucketKey, Parts>> readMany(Collection<Labels> series, Collection<BucketKey> keys) {
        return inner.readMany(series, keys);
    }

    @Override
    public Parts sum(Labels series, Grain grain, long fromStart, long toStart) {
        return inner.sum(series, grain, fromStart, toStart);
    }

    @Override
    public SortedMap<Long, Parts> buckets(Labels series, Grain grain, long fromStart, long toStart) {
        return inner.buckets(series, grain, fromStart, toStart);
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
    public List<Labels> series(List<Matcher> matchers, int limit) {
        return inner.series(matchers, limit);
    }

    @Override
    public Set<String> labelValues(String label) {
        return inner.labelValues(label);
    }

    @Override
    public RingSnapshot snapshot(Labels series, String key) {
        return inner.snapshot(series, key);
    }

    @Override
    public boolean putSnapshot(Labels series, String key, RingSnapshot snapshot) {
        return inner.putSnapshot(series, key, snapshot);
    }

    @Override
    public void expire(Grain grain, long beforeStart) {
        inner.expire(grain, beforeStart);
    }

    @Override
    public int expireClaims(long nowMs) {
        return inner.expireClaims(nowMs);
    }

    @Override
    public int removeIdle(String label, long lastWriteBeforeMs) {
        return inner.removeIdle(label, lastWriteBeforeMs);
    }

    @Override
    public void export(StoreSink sink) {
        inner.export(sink);
    }

    @Override
    public StoreSink mergeMaxSink() {
        return inner.mergeMaxSink();
    }
}
