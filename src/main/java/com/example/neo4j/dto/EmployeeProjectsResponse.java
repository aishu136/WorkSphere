package com.example.neo4j.dto;

import java.util.List;

/**
 * An employee's projects. allocationPercent is the total over open projects
 * (PLANNED, ACTIVE, ON_HOLD); finished projects are listed as history but don't count.
 */
public record EmployeeProjectsResponse(int allocationPercent, List<EmployeeAssignmentResponse> assignments) {
}
