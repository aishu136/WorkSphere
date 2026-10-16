package com.example.neo4j.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Counts working days: Monday to Friday, minus public holidays when they are given (an office's
 * holidays; see OfficeService). Without them, every weekday counts.
 */
final class WorkingDays {

    private WorkingDays() {
    }

    // Both dates inclusive; 0 if end is before start.
    static int between(LocalDate start, LocalDate end) {
        return between(start, end, Set.of());
    }

    static int between(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(day)) {
                days++;
            }
        }
        return days;
    }

    // The working days of start..end that fall in the given calendar year.
    static int inYear(int year, LocalDate start, LocalDate end) {
        return inYear(year, start, end, Set.of());
    }

    static int inYear(int year, LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        return overlap(start, end, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), holidays);
    }

    // The working days of start..end that fall between from and to (all inclusive).
    static int overlap(LocalDate start, LocalDate end, LocalDate from, LocalDate to) {
        return overlap(start, end, from, to, Set.of());
    }

    static int overlap(LocalDate start, LocalDate end, LocalDate from, LocalDate to, Set<LocalDate> holidays) {
        return between(max(start, from), min(end, to), holidays);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
