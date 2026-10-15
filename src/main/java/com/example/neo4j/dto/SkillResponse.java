package com.example.neo4j.dto;

// level is 1-5: how good an employee is, or for a project's required skills, the minimum it needs.
public record SkillResponse(String id, String name, int level) {
}
