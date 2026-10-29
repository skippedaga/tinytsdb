package io.github.skippedaga.tinytsdb;

/**
 * A store whose expiry {@link TinyTsdb#maintain} runs. A store that prunes on write, or lets its backend expire keys
 * (TTLs), need not implement it; {@code maintain()} then does nothing for it.
 */
public interface MaintainableStore extends CounterStore {

    /** Drops the {@code grain} buckets that start before {@code beforeStart}, and snapshots of that grain. */
    void expire(Grain grain, long beforeStart);

    /** Drops claims that expired by {@code nowMs}; returns how many. */
    int expireClaims(long nowMs);

    /**
     * Removes entirely the series that have {@code label} and no write since {@code lastWriteBeforeMs}.
     *
     * @return how many series were removed
     */
    int removeIdle(String label, long lastWriteBeforeMs);
}
