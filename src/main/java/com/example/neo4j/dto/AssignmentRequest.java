package com.example.neo4j.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Adds someone to a project, or changes their role, allocation or dates on it.
 * Dates are inclusive. For someone new, they default to the project's dates, starting no earlier
 * than today, and a missing endDate means open-ended. For someone already on the project, a date
 * left out keeps its current value. openEnded: true removes the end date, so the assignment runs
 * until further notice even if the project or the current assignment has an end date.
 */
public record AssignmentRequest(

        // e.g. "Backend developer", "Tech lead"
        @Size(max = 100)
        String role,

        // Share of the person's time on this project.
        @NotNull
        @Min(1)
        @Max(100)
        Integer allocationPercent,

        LocalDate startDate,

        LocalDate endDate,

        Boolean openEnded) {
}
