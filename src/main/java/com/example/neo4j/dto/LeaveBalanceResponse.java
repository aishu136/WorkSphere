package com.example.neo4j.dto;

/**
 * Annual leave for one calendar year, in working days. remainingDays is what can still be
 * requested: the allowance minus approved and pending days.
 */
public record LeaveBalanceResponse(
        String employeeId,
        int year,
        int allowanceDays,
        int approvedDays,
        int pendingDays,
        int remainingDays) {
}
