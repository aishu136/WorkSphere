package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Department code is the permanent business key and cannot be changed.
public record UpdateDepartmentRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description) {
}
