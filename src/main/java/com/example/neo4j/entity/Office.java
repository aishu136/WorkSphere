package com.example.neo4j.entity;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

// Employees link to an office with LOCATED_AT, managed with Cypher in EmployeeQueries.
@Node("Office")
@Getter
@Setter
public class Office {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Short unique business key, e.g. "BLR-01".
    private String code;

    private String name;

    private String city;

    private String country;

    private String address;

    // Optional number of desks.
    private Integer capacity;
}
