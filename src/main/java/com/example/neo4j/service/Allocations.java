package com.example.neo4j.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.neo4j.repository.ProjectQueries.Booking;

/**
 * Allocation over time. Someone's load on a day is the sum of their bookings that cover that day,
 * and the rule is that it never goes over 100% on any day.
 */
final class Allocations {

    /** The highest load in a date range, and the first day it is reached. */
    record Peak(int percent, LocalDate day) {
    }

    private Allocations() {
    }

    /**
     * The busiest day between from and to (inclusive; a null to means open-ended).
     * Load only goes up on a day a booking starts, so it is enough to look at from and at every
     * booking start inside the range.
     */
    static Peak peak(List<Booking> bookings, LocalDate from, LocalDate to) {

        List<LocalDate> days = new ArrayList<>();
        days.add(from);
        for (Booking booking : bookings) {
            LocalDate start = startOf(booking);
            if (start.isAfter(from) && (to == null || !start.isAfter(to))) {
                days.add(start);
            }
        }

        Peak peak = new Peak(0, from);
        for (LocalDate day : days.stream().sorted().distinct().toList()) {
            int load = loadOn(bookings, day);
            if (load > peak.percent()) {
                peak = new Peak(load, day);
            }
        }
        return peak;
    }

    static int loadOn(List<Booking> bookings, LocalDate day) {
        return bookings.stream()
                .filter(b -> !startOf(b).isAfter(day) && (b.endDate() == null || !b.endDate().isBefore(day)))
                .mapToInt(Booking::allocationPercent)
                .sum();
    }

    // Bookings from before assignments had dates are migrated, so a missing start is only a safety net.
    private static LocalDate startOf(Booking booking) {
        return booking.startDate() == null ? LocalDate.MIN : booking.startDate();
    }
}
