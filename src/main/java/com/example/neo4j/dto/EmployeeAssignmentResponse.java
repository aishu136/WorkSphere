package com.example.neo4j.dto;

import java.time.LocalDate;

// One project an employee is on.
// since: when they joined. startDate to endDate (null: open-ended): when the allocation applies.
public record EmployeeAssignmentResponse(ProjectSummary project, String role, int allocationPercent, LocalDate since,
                                         LocalDate startDate, LocalDate endDate) {
}
