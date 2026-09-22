package com.example.neo4j.entity;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

// PART_OF (parent department) and HEADED_BY (head employee) are managed with Cypher
// in DepartmentQueries, for the same reason as on Employee.
@Node("Department")
@Getter
@Setter
public class Department {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Short unique business key, e.g. "ENG" or "HR-PAYROLL".
    private String code;

    private String name;

    private String description;
}
