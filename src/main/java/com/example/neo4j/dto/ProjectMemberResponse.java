package com.example.neo4j.dto;

import java.time.LocalDate;

// One person on a project.
public record ProjectMemberResponse(EmployeeSummary employee, String role, int allocationPercent, LocalDate since) {
}
