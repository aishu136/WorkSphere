package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SkillRequest(

        @NotBlank
        @Size(max = 100)
        String name) {
}
