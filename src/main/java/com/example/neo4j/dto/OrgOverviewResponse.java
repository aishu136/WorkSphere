package com.example.neo4j.dto;

/**
 * The shape of the organisation, counting current (not terminated) employees.
 *
 * Span of control is how many people report directly to a manager. orgDepth is the number of
 * levels in the longest reporting line, from the top of the organisation down.
 */
public record OrgOverviewResponse(
        long headcount,
        long onLeave,
        long managers,
        double averageSpan,
        int widestSpan,
        int orgDepth,
        // Nobody to report to: the top of the organisation, or people not placed yet.
        long withoutManager,
        long withoutDepartment) {
}
