package com.example.neo4j.dto;

import java.time.LocalDate;

import com.example.neo4j.entity.LeaveType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** An employee's request for their own time off. Both dates are inclusive. */
public record CreateLeaveRequest(

        @NotNull
        LeaveType type,

        @NotNull
        LocalDate startDate,

        @NotNull
        LocalDate endDate,

        // Shown to the approver.
        @Size(max = 500)
        String note) {
}
