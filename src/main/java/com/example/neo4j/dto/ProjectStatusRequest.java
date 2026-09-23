package com.example.neo4j.dto;

import com.example.neo4j.entity.ProjectStatus;

import jakarta.validation.constraints.NotNull;

public record ProjectStatusRequest(

        @NotNull
        ProjectStatus status) {
}
