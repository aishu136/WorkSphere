package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateDepartmentRequest(

        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9-]{2,20}", message = "must be 2-20 letters, digits or -")
        String code,

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description,

        // Optional: create it directly as a sub-department.
        String parentId) {
}
