package com.example.neo4j.dto;

import java.time.LocalDate;
import java.util.List;

import com.example.neo4j.entity.EmploymentStatus;

public record EmployeeResponse(
        String id,
        String employeeCode,
        String name,
        String email,
        String jobTitle,
        LocalDate hireDate,
        EmploymentStatus status,
        LocalDate terminationDate,
        DepartmentSummary department,
        EmployeeSummary manager,
        CompanyResponse company,
        List<SkillResponse> skills,
        long directReportCount) {
}
