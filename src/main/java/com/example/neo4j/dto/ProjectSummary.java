package com.example.neo4j.dto;

import com.example.neo4j.entity.ProjectStatus;

public record ProjectSummary(String id, String code, String name, ProjectStatus status) {
}
