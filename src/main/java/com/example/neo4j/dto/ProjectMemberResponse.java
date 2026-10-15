package com.example.neo4j.dto;

import java.time.LocalDate;

// One person on a project.
// since: when they joined. startDate to endDate (null: open-ended): when the allocation applies.
public record ProjectMemberResponse(EmployeeSummary employee, String role, int allocationPercent, LocalDate since,
                                    LocalDate startDate, LocalDate endDate) {
}
