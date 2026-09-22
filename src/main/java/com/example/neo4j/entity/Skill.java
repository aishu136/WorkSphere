package com.example.neo4j.entity;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Data;

@Node("Skill")
@Data
public class Skill {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    private String name;

    // getters and setters
}
