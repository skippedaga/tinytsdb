package io.github.skippedaga.tinytsdb;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
final class TestClock extends Clock {

    private volatile long now;

    TestClock(long now) {
        this.now = now;
    }

    void set(long ms) {
        now = ms;
    }

    void advance(long ms) {
        now += ms;
    }

    @Override
    public long millis() {
        return now;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(now);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
