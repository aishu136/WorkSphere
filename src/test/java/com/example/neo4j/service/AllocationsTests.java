package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.neo4j.repository.ProjectQueries.Booking;
import com.example.neo4j.service.Allocations.Peak;

class AllocationsTests {

    private static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2030, month, dayOfMonth);
    }

    @Test
    void theBusiestDayCountsOnlyBookingsThatOverlapIt() {
        // 60% in January, 50% from mid-January to February, 30% open-ended from March.
        List<Booking> bookings = List.of(
                new Booking(day(1, 1), day(1, 31), 60),
                new Booking(day(1, 15), day(2, 28), 50),
                new Booking(day(3, 1), null, 30));

        assertThat(Allocations.peak(bookings, day(1, 1), day(12, 31))).isEqualTo(new Peak(110, day(1, 15)));
        assertThat(Allocations.peak(bookings, day(2, 1), day(12, 31))).isEqualTo(new Peak(50, day(2, 1)));
        assertThat(Allocations.peak(bookings, day(3, 1), null)).isEqualTo(new Peak(30, day(3, 1)));

        // End dates are inclusive: on 31 January both January bookings still count.
        assertThat(Allocations.loadOn(bookings, day(1, 31))).isEqualTo(110);
        assertThat(Allocations.loadOn(bookings, day(2, 1))).isEqualTo(50);
    }

    @Test
    void nothingBookedMeansZeroFromTheFirstDay() {
        assertThat(Allocations.peak(List.of(), day(5, 1), day(5, 31))).isEqualTo(new Peak(0, day(5, 1)));
        // A booking that starts after the range doesn't count.
        assertThat(Allocations.peak(List.of(new Booking(day(6, 1), null, 100)), day(5, 1), day(5, 31)).percent())
                .isZero();
    }
}
