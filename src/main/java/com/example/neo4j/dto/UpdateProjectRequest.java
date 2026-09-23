package com.example.neo4j.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Project code is the permanent business key. Status has its own endpoint.
public record UpdateProjectRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 1000)
        String description,

        LocalDate startDate,

        LocalDate endDate) {
}
