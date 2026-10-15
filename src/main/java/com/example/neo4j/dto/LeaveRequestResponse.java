package com.example.neo4j.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.LeaveType;

public record LeaveRequestResponse(
        String id,
        EmployeeSummary employee,
        LeaveType type,
        LocalDate startDate,
        LocalDate endDate,
        // Monday to Friday between the dates.
        int workingDays,
        String note,
        LeaveStatus status,
        String requestedBy,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String reason) {
}
