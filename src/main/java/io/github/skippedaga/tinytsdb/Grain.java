package io.github.skippedaga.tinytsdb;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * The size of a time bucket. Second, minute and hour buckets are in UTC; day, ISO week, month and year buckets follow
 * the store's zone, so a day starts at local midnight (and is 23 or 25 hours long on a DST change). {@link #ALL} is
 * one bucket for all time.
 *
 * <p>Every bucket has an id that parses back to its range: {@code s:2026-10-29T13:05:07}, {@code m:2026-10-29T13:05},
 * {@code h:2026-10-29T13}, {@code d:2026-10-29}, {@code w:2026-W44}, {@code mo:2026-10}, {@code y:2026},
 * {@code all}.</p>
 */
public enum Grain {

    SECOND("s", 1_000L),
    MINUTE("m", 60_000L),
    HOUR("h", 3_600_000L),
    DAY("d", 0),
    WEEK("w", 0),
    MONTH("mo", 0),
    YEAR("y", 0),
    ALL("all", 0);

    private static final DateTimeFormatter SECOND_ID = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MINUTE_ID = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm", Locale.ROOT);
    private static final DateTimeFormatter HOUR_ID = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH", Locale.ROOT);

    private final String prefix;
    private final long stepMs;

    Grain(String prefix, long stepMs) {
        this.prefix = prefix;
        this.stepMs = stepMs;
    }

    /** The id prefix: {@code s m h d w mo y all}. */
    public String prefix() {
        return prefix;
    }

    /** True for second, minute and hour: a fixed length in UTC. */
    public boolean isFixed() {
        return stepMs > 0;
    }

    /** The fixed length in ms, or 0 for zoned grains and {@link #ALL}. */
    public long stepMs() {
        return stepMs;
    }

    /** Start of the bucket holding {@code epochMs}. */
    public long floor(long epochMs, ZoneId zone) {
        if (this == ALL) {
            return 0;
        }
        if (isFixed()) {
            return Math.floorDiv(epochMs, stepMs) * stepMs;
        }
        LocalDate date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
        LocalDate start = switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
            default -> throw new IllegalStateException(name());
        };
        return start.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Start of the bucket {@code steps} after the one starting at {@code start} (before, when negative). */
    public long plus(long start, long steps, ZoneId zone) {
        if (this == ALL) {
            throw new IllegalArgumentException("ALL has a single bucket");
        }
        if (isFixed()) {
            return Math.addExact(start, Math.multiplyExact(steps, stepMs));
        }
        LocalDate date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
        LocalDate moved = switch (this) {
            case DAY -> date.plusDays(steps);
            case WEEK -> date.plusWeeks(steps);
            case MONTH -> date.plusMonths(steps);
            case YEAR -> date.plusYears(steps);
            default -> throw new IllegalStateException(name());
        };
        return moved.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** End of the bucket starting at {@code start} (exclusive). */
    public long end(long start, ZoneId zone) {
        return this == ALL ? Long.MAX_VALUE : plus(start, 1, zone);
    }

    /** How many buckets lie between two bucket starts ({@code to - from}, in steps). */
    public long stepsBetween(long fromStart, long toStart, ZoneId zone) {
        if (isFixed()) {
            return (toStart - fromStart) / stepMs;
        }
        LocalDate a = Instant.ofEpochMilli(fromStart).atZone(zone).toLocalDate();
        LocalDate b = Instant.ofEpochMilli(toStart).atZone(zone).toLocalDate();
        return switch (this) {
            case DAY -> ChronoUnit.DAYS.between(a, b);
            case WEEK -> ChronoUnit.WEEKS.between(a, b);
            case MONTH -> ChronoUnit.MONTHS.between(a, b);
            case YEAR -> ChronoUnit.YEARS.between(a, b);
            default -> throw new IllegalArgumentException("ALL has a single bucket");
        };
    }

    /** The id of the bucket holding {@code epochMs}. */
    public String id(long epochMs, ZoneId zone) {
        if (this == ALL) {
            return "all";
        }
        long start = floor(epochMs, zone);
        if (isFixed()) {
            LocalDateTime t = LocalDateTime.ofEpochSecond(Math.floorDiv(start, 1000), 0, ZoneOffset.UTC);
            DateTimeFormatter f = this == SECOND ? SECOND_ID : this == MINUTE ? MINUTE_ID : HOUR_ID;
            return prefix + ':' + f.format(t);
        }
        LocalDate d = Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
        return switch (this) {
            case DAY -> "d:" + d;
            case WEEK -> String.format(Locale.ROOT, "w:%04d-W%02d",
                    d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> "mo:" + YearMonth.from(d);
            case YEAR -> String.format(Locale.ROOT, "y:%04d", d.getYear());
            default -> throw new IllegalStateException(name());
        };
    }

    /** A bucket: its grain and {@code [start, end)}. */
    public record Bucket(Grain grain, long start, long end) {
    }

    /**
     * Parses a bucket id back to its range.
     *
     * @throws IllegalArgumentException on an unknown prefix or a malformed id
     */
    public static Bucket parse(String id, ZoneId zone) {
        if ("all".equals(id)) {
            return new Bucket(ALL, Long.MIN_VALUE, Long.MAX_VALUE);
        }
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Not a bucket id: " + id);
        }
        String p = id.substring(0, colon);
        String v = id.substring(colon + 1);
        try {
            for (Grain g : values()) {
                if (!g.prefix.equals(p) || g == ALL) {
                    continue;
                }
                long start = switch (g) {
                    case SECOND -> LocalDateTime.parse(v, SECOND_ID).toInstant(ZoneOffset.UTC).toEpochMilli();
                    case MINUTE -> LocalDateTime.parse(v, MINUTE_ID).toInstant(ZoneOffset.UTC).toEpochMilli();
                    case HOUR -> LocalDateTime.parse(v + ":00", MINUTE_ID).toInstant(ZoneOffset.UTC).toEpochMilli();
                    case DAY -> LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli();
                    case WEEK -> week(v, zone);
                    case MONTH -> YearMonth.parse(v).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
                    case YEAR -> LocalDate.of(Integer.parseInt(v), 1, 1).atStartOfDay(zone).toInstant().toEpochMilli();
                    default -> throw new IllegalStateException(g.name());
                };
                if (!g.id(start, zone).equals(id)) {
                    throw new IllegalArgumentException("Not a canonical bucket id: " + id);
                }
                return new Bucket(g, start, g.end(start, zone));
            }
        } catch (DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException("Malformed bucket id: " + id, e);
        }
        throw new IllegalArgumentException("Unknown bucket prefix in " + id);
    }

    private static long week(String v, ZoneId zone) {
        int dash = v.indexOf("-W");
        if (dash < 0) {
            throw new IllegalArgumentException("Malformed week id: w:" + v);
        }
        int year = Integer.parseInt(v.substring(0, dash));
        int week = Integer.parseInt(v.substring(dash + 2));
        LocalDate monday = LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        ZonedDateTime start = monday.atStartOfDay(zone);
        return start.toInstant().toEpochMilli();
    }
}
