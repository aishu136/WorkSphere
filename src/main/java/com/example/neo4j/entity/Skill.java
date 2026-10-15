package com.example.neo4j.entity;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Data;

/*
 * Levels live on the relationships, not here: (:Employee)-[:HAS_SKILL {level}]->(:Skill) is how good
 * someone is, (:Project)-[:REQUIRES {level}]->(:Skill) is the minimum a project needs.
 */
@Node("Skill")
@Data
public class Skill {

    // 1 = beginner, 2 = basic, 3 = intermediate, 4 = advanced, 5 = expert.
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 5;
    public static final int DEFAULT_LEVEL = 3;

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    private String name;
}
