package com.example.neo4j.dto;

import java.time.LocalDate;

import com.example.neo4j.entity.ProjectStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(

        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9-]{2,30}", message = "must be 2-30 letters, digits or -")
        String code,

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 1000)
        String description,

        // Defaults to PLANNED.
        ProjectStatus status,

        LocalDate startDate,

        LocalDate endDate,

        // Optional owning department and project lead.
        String departmentId,

        String leadId) {
}
