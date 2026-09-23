package com.example.neo4j.dto;

import java.time.LocalDate;

// One project an employee is on.
public record EmployeeAssignmentResponse(ProjectSummary project, String role, int allocationPercent, LocalDate since) {
}
