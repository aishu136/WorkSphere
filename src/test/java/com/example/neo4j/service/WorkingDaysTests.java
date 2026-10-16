package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingDaysTests {

    // 4 March 2030 is a Monday.
    private static final LocalDate MON = LocalDate.of(2030, 3, 4);

    @Test
    void countsMondayToFridayInclusive() {
        assertThat(WorkingDays.between(MON, MON)).isEqualTo(1);
        assertThat(WorkingDays.between(MON, MON.plusDays(4))).isEqualTo(5);
        assertThat(WorkingDays.between(MON, MON.plusDays(6))).isEqualTo(5);
        assertThat(WorkingDays.between(MON.plusDays(5), MON.plusDays(6))).isZero();
        assertThat(WorkingDays.between(MON, MON.plusDays(13))).isEqualTo(10);
        assertThat(WorkingDays.between(MON.plusDays(1), MON)).isZero();
    }

    @Test
    void officeHolidaysAreNotWorkingDays() {
        // Wednesday is a holiday; a holiday on a Saturday changes nothing.
        Set<LocalDate> holidays = Set.of(MON.plusDays(2), MON.plusDays(5));

        assertThat(WorkingDays.between(MON, MON.plusDays(6), holidays)).isEqualTo(4);
        assertThat(WorkingDays.between(MON.plusDays(2), MON.plusDays(2), holidays)).isZero();
        assertThat(WorkingDays.overlap(MON, MON.plusDays(13), MON.plusDays(1), MON.plusDays(3), holidays)).isEqualTo(2);
    }

    @Test
    void splitsAcrossYears() {
        LocalDate start = LocalDate.of(2030, 12, 23);
        LocalDate end = LocalDate.of(2031, 1, 3);

        assertThat(WorkingDays.inYear(2030, start, end)).isEqualTo(7);
        assertThat(WorkingDays.inYear(2031, start, end)).isEqualTo(3);
        assertThat(WorkingDays.inYear(2029, start, end)).isZero();
    }
}
