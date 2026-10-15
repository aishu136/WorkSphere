package com.example.neo4j.dto;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.example.neo4j.entity.LeaveStatus;

/**
 * Optional leave request filters from query parameters, e.g. /leave-requests?status=APPROVED&from=2026-12-01&to=2026-12-31.
 * from/to find requests that overlap those dates, so a team calendar can ask who is away in a month.
 */
public record LeaveFilter(

        String employeeId,

        LeaveStatus status,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate to) {

    public LeaveFilter forEmployee(String id) {
        return new LeaveFilter(id, status, from, to);
    }
}
