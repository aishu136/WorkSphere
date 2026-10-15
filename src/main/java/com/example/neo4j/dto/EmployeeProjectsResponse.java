package com.example.neo4j.dto;

import java.util.List;

/**
 * An employee's projects. allocationPercent is the total today over open projects
 * (PLANNED, ACTIVE, ON_HOLD); finished projects, and assignments that haven't started or have
 * ended, are listed but don't count.
 */
public record EmployeeProjectsResponse(int allocationPercent, List<EmployeeAssignmentResponse> assignments) {
}
