package com.example.neo4j.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Adds someone to a project, or changes their role or allocation on it. */
public record AssignmentRequest(

        // e.g. "Backend developer", "Tech lead"
        @Size(max = 100)
        String role,

        // Share of the person's time on this project.
        @NotNull
        @Min(1)
        @Max(100)
        Integer allocationPercent) {
}
