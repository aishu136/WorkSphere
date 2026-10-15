package com.example.neo4j.dto;

import java.time.LocalDate;
import java.util.List;

import com.example.neo4j.entity.ProjectStatus;

public record ProjectResponse(
        String id,
        String code,
        String name,
        String description,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        DepartmentSummary department,
        EmployeeSummary lead,
        long memberCount,
        // Skills the project needs, each with the minimum level, by name.
        List<SkillResponse> requiredSkills) {
}
