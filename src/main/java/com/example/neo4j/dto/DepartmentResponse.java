package com.example.neo4j.dto;

public record DepartmentResponse(
        String id,
        String code,
        String name,
        String description,
        DepartmentSummary parent,
        EmployeeSummary head,
        long memberCount,
        long subDepartmentCount) {
}
