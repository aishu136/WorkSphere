package com.example.neo4j.dto;

import java.time.Instant;

import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;

public record StaffingRequestResponse(
        String id,
        ProjectSummary project,
        EmployeeSummary employee,
        StaffingAction action,
        String role,
        Integer allocationPercent,
        StaffingRequestStatus status,
        String requestedBy,
        Instant requestedAt,
        String decidedBy,
        Instant decidedAt,
        String reason) {
}
