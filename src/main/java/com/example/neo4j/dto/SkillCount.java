package com.example.neo4j.dto;

/** A skill name and how many current (not terminated) employees have it. */
public record SkillCount(String name, long employees) {
}
