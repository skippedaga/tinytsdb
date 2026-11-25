package io.github.skippedaga.tinytsdb.cold;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Where {@link ColdGroups} keeps the groups it took out of memory: one row per group (its key, when it was evicted,
 * its buckets as {@link GroupCodec} bytes). The application implements it (a database table, files); tinytsdb has
 * none. A failure is thrown as the implementation's own unchecked exception: {@link ColdGroups} passes it on to the
 * caller of {@link ColdGroups#ensureLoaded} and retries the background loads and the evictions later.
 *
 * <p>Several processes may share one store and evict the same group on their own: a row is replaced only by a later
 * eviction ({@code evictedAtMs} not lower), so one that lags behind never writes an older state over a newer one.</p>
 */
public interface ColdStore {

    /** One group's row. */
    record Row(String key, long evictedAtMs, byte[] data) {
    }

    /** Writes these rows; a row held is replaced only by one evicted at the same time or later. */
    void saveAll(List<Row> rows);

    /** The rows of these keys; a key without one is absent. */
    Map<String, Row> readMany(Collection<String> keys);

    /**
     * Drops the rows evicted before {@code beforeMs}: nothing of them is wanted any more.
     *
     * @return how many were dropped
     */
    int deleteEvictedBefore(long beforeMs);
}
