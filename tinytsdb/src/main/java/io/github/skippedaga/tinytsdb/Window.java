package io.github.skippedaga.tinytsdb;

import java.time.Duration;

/** What span of time a read covers. */
public sealed interface Window {

    /** One calendar bucket by its id: {@code d:2026-10-29}, {@code w:2026-W44}, {@code mo:2026-10}… */
    record Calendar(String id) implements Window {
    }

    /**
     * The last {@code steps} buckets of {@code grain}, the one in progress included: {@code [start of (current step −
     * (steps − 1)), end of current step)}. Exact to the step. Zoned grains count calendar steps in the store's zone.
     */
    record Rolling(int steps, Grain grain) implements Window {

        public Rolling {
            if (steps <= 0) {
                throw new IllegalArgumentException("steps must be positive");
            }
            if (grain == Grain.ALL) {
                throw new IllegalArgumentException("ALL has a single bucket");
            }
        }
    }

    /** {@code [fromMs, toMs)}, made of the coarsest buckets that fit, finer ones at the edges. */
    record Range(long fromMs, long toMs) implements Window {

        public Range {
            if (toMs < fromMs) {
                throw new IllegalArgumentException("toMs before fromMs");
            }
        }
    }

    /**
     * A window of fixed {@code length} that opens with the series' first event with a positive delta on
     * {@code anchorMetric} (same labels otherwise), from the start of the finest bucket holding it. Not opened yet:
     * an empty reading.
     *
     * @param recomputeOnRetraction when the anchor's bucket no longer has a positive net value (the event was
     *                              retracted), move the anchor to the first bucket that still has one. Costs a scan
     *                              of the anchor series' finest buckets on such reads.
     */
    record Anchored(String anchorMetric, Duration length, boolean recomputeOnRetraction) implements Window {

        public Anchored {
            Labels.checkMetricName(anchorMetric);
            if (length.isNegative() || length.isZero()) {
                throw new IllegalArgumentException("length must be positive");
            }
        }
    }

    /** Everything ever counted. */
    record AllTime() implements Window {
    }

    static Window calendar(String id) {
        return new Calendar(id);
    }

    static Window rolling(int steps, Grain grain) {
        return new Rolling(steps, grain);
    }

    /**
     * A rolling window given as a duration: {@code PT5M} at {@link Grain#MINUTE} is 5 steps, {@code P7D} at
     * {@link Grain#DAY} is 7 zoned days. Days count as 24 hours and weeks as 7 days for the conversion.
     *
     * @throws IllegalArgumentException when the duration is not a whole number of steps
     */
    static Window rolling(Duration length, Grain grain) {
        long step = switch (grain) {
            case SECOND, MINUTE, HOUR -> grain.stepMs();
            case DAY -> Duration.ofDays(1).toMillis();
            case WEEK -> Duration.ofDays(7).toMillis();
            default -> throw new IllegalArgumentException("Give months and years as steps: rolling(n, " + grain + ")");
        };
        long ms = length.toMillis();
        if (ms <= 0 || ms % step != 0) {
            throw new IllegalArgumentException(length + " is not a whole number of " + grain + " steps");
        }
        return new Rolling(Math.toIntExact(ms / step), grain);
    }

    static Window range(long fromMs, long toMs) {
        return new Range(fromMs, toMs);
    }

    static Window anchored(String anchorMetric, Duration length) {
        return new Anchored(anchorMetric, length, false);
    }

    static Window allTime() {
        return new AllTime();
    }
}
