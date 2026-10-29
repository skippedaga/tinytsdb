package io.github.skippedaga.tinytsdb;

/**
 * One change of one counter: {@code delta} (negative to retract) to {@code metric} with {@code labels}, at the event's
 * own time {@code occurredAtMs} — a correction goes back to the time of the event it corrects.
 */
public record Increment(String metric, Labels labels, long occurredAtMs, long delta) {

    public Increment {
        Labels.checkMetricName(metric);
    }

    public static Increment of(String metric, Labels labels, long occurredAtMs, long delta) {
        return new Increment(metric, labels, occurredAtMs, delta);
    }
}
