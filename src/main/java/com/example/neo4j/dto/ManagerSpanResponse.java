package com.example.neo4j.dto;

/**
 * One manager's span of control. layersBelow is how many levels of reports they have.
 * flag is WIDE above the configured maximum span, NARROW with a single report, otherwise null.
 */
public record ManagerSpanResponse(
        EmployeeSummary manager,
        int directReports,
        int totalReports,
        int layersBelow,
        String flag) {
}
