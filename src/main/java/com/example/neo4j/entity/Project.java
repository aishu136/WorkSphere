package com.example.neo4j.entity;

import java.time.LocalDate;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

// WORKS_ON (members), LED_BY (lead) and OWNED_BY (department) are managed with Cypher in ProjectQueries.
@Node("Project")
@Getter
@Setter
public class Project {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Short unique business key, e.g. "PAYROLL-2026".
    private String code;

    private String name;

    private String description;

    private ProjectStatus status = ProjectStatus.PLANNED;

    private LocalDate startDate;

    private LocalDate endDate;
}
