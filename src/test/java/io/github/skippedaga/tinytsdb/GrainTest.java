package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrainTest {

    static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void utcGrainsAreFixed() {
        long t = at("2026-10-29T13:05:07.123Z");

        assertThat(Grain.SECOND.floor(t, KYIV)).isEqualTo(at("2026-10-29T13:05:07Z"));
        assertThat(Grain.MINUTE.floor(t, KYIV)).isEqualTo(at("2026-10-29T13:05:00Z"));
        assertThat(Grain.HOUR.floor(t, KYIV)).isEqualTo(at("2026-10-29T13:00:00Z"));
        assertThat(Grain.SECOND.id(t, KYIV)).isEqualTo("s:2026-10-29T13:05:07");
        assertThat(Grain.MINUTE.id(t, KYIV)).isEqualTo("m:2026-10-29T13:05");
        assertThat(Grain.HOUR.id(t, KYIV)).isEqualTo("h:2026-10-29T13");
    }

    @Test
    void aDayStartsAtLocalMidnight() {
        // 23:30 UTC on the 28th is 01:30 on the 29th in Kyiv (UTC+2 after the October change)
        long t = at("2026-10-28T23:30:00Z");

        assertThat(Grain.DAY.id(t, KYIV)).isEqualTo("d:2026-10-29");
        assertThat(Grain.DAY.floor(t, KYIV)).isEqualTo(at("2026-10-28T22:00:00Z"));
        assertThat(Grain.DAY.id(t, ZoneOffset.UTC)).isEqualTo("d:2026-10-28");
    }

    @Test
    void dstDaysAre23And25Hours() {
        long spring = Grain.DAY.floor(at("2026-03-29T12:00:00Z"), KYIV);
        long autumn = Grain.DAY.floor(at("2026-10-25T12:00:00Z"), KYIV);

        assertThat(Grain.DAY.end(spring, KYIV) - spring).isEqualTo(23 * 3_600_000L);
        assertThat(Grain.DAY.end(autumn, KYIV) - autumn).isEqualTo(25 * 3_600_000L);
        assertThat(Grain.DAY.plus(spring, 7, KYIV))
                .isEqualTo(LocalDate.of(2026, 4, 5).atStartOfDay(KYIV).toInstant().toEpochMilli());
        assertThat(Grain.DAY.stepsBetween(spring, Grain.DAY.plus(spring, 7, KYIV), KYIV)).isEqualTo(7);
    }

    @Test
    void isoWeeksUseTheWeekBasedYear() {
        assertThat(Grain.WEEK.id(at("2026-10-29T12:00:00Z"), KYIV)).isEqualTo("w:2026-W44");
        // 2026-12-31 is a Thursday: ISO week 53 of 2026; 2027-01-01 (Friday) is in it too
        assertThat(Grain.WEEK.id(at("2027-01-01T12:00:00Z"), ZoneOffset.UTC)).isEqualTo("w:2026-W53");
        // 2024-12-30 (Monday) starts week 1 of 2025
        assertThat(Grain.WEEK.id(at("2024-12-30T12:00:00Z"), ZoneOffset.UTC)).isEqualTo("w:2025-W01");
        assertThat(Grain.WEEK.floor(at("2026-11-01T21:59:00Z"), KYIV))
                .isEqualTo(ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, KYIV).toInstant().toEpochMilli());
    }

    @Test
    void monthsAndYears() {
        long t = at("2026-10-29T12:00:00Z");

        assertThat(Grain.MONTH.id(t, KYIV)).isEqualTo("mo:2026-10");
        assertThat(Grain.YEAR.id(t, KYIV)).isEqualTo("y:2026");
        assertThat(Grain.MONTH.plus(Grain.MONTH.floor(t, KYIV), 3, KYIV))
                .isEqualTo(LocalDate.of(2027, 1, 1).atStartOfDay(KYIV).toInstant().toEpochMilli());
    }

    @ParameterizedTest
    @ValueSource(strings = {"s:2026-10-29T13:05:07", "m:2026-10-29T13:05", "h:2026-10-29T13", "d:2026-10-29",
            "d:2026-03-29", "w:2026-W44", "w:2026-W53", "w:2025-W01", "mo:2026-10", "y:2026"})
    void everyIdParsesBackToTheBucketItNames(String id) {
        Grain.Bucket b = Grain.parse(id, KYIV);

        assertThat(b.grain().id(b.start(), KYIV)).isEqualTo(id);
        assertThat(b.grain().id(b.end() - 1, KYIV)).isEqualTo(id);
        assertThat(b.grain().id(b.end(), KYIV)).isNotEqualTo(id);
        assertThat(b.end()).isEqualTo(b.grain().end(b.start(), KYIV));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "d:", "d:2026-13-01", "x:2026", "w:2026-W54", "mo:2026-1", "d2026-10-29",
            "h:2026-10-29T25"})
    void malformedIdsAreRefused(String id) {
        assertThatThrownBy(() -> Grain.parse(id, KYIV)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allIsOneBucket() {
        assertThat(Grain.ALL.floor(at("2026-10-29T12:00:00Z"), KYIV)).isZero();
        assertThat(Grain.ALL.id(123, KYIV)).isEqualTo("all");
        assertThat(Grain.parse("all", KYIV).grain()).isEqualTo(Grain.ALL);
    }
}
