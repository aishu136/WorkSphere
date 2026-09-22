package com.example.neo4j.audit;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.Size;

/**
 * Optional filters from query parameters, e.g.
 * /audit?actor=priya&action=EMPLOYEE_UPDATED&targetType=EMPLOYEE&targetId=...&from=2026-01-01T00:00:00Z
 */
public record AuditFilter(

        @Size(max = 100)
        String actor,

        AuditAction action,

        AuditTargetType targetType,

        @Size(max = 100)
        String targetId,

        // Inclusive lower bound, ISO-8601 (e.g. 2026-01-01T00:00:00Z).
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        OffsetDateTime from,

        // Exclusive upper bound.
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        OffsetDateTime to) {

    public static AuditFilter forTarget(AuditTargetType type, String id) {
        return new AuditFilter(null, null, type, id, null, null);
    }
}
