package com.example.neo4j.dto;

import com.example.neo4j.entity.EmploymentStatus;

import jakarta.validation.constraints.NotNull;

public record StatusRequest(

        @NotNull
        EmploymentStatus status) {
}
