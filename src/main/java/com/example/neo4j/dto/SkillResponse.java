package com.example.neo4j.dto;

import com.example.neo4j.entity.Skill;

public record SkillResponse(String id, String name) {

    public static SkillResponse from(Skill skill) {
        return new SkillResponse(skill.getId(), skill.getName());
    }
}
