package com.example.neo4j.dto;

import com.example.neo4j.entity.Skill;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A skill and its level, 1 (beginner) to 5 (expert). For a project, the level is the minimum it needs.
 * Without a level, a skill already held keeps its level and a new one gets 3 (intermediate).
 */
public record SkillRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @Min(Skill.MIN_LEVEL)
        @Max(Skill.MAX_LEVEL)
        Integer level) {
}
