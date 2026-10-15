package com.example.neo4j.service;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Counts Monday-to-Friday days. Public holidays are not known to WorkSphere, so they count as working days. */
final class WorkingDays {

    private WorkingDays() {
    }

    // Both dates inclusive; 0 if end is before start.
    static int between(LocalDate start, LocalDate end) {
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    // The working days of start..end that fall in the given calendar year.
    static int inYear(int year, LocalDate start, LocalDate end) {
        return overlap(start, end, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    // The working days of start..end that fall between from and to (all inclusive).
    static int overlap(LocalDate start, LocalDate end, LocalDate from, LocalDate to) {
        return between(max(start, from), min(end, to));
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
