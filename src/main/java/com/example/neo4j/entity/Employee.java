package com.example.neo4j.entity;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/*
 * Only the relationship to Company is mapped here.
 * REPORTS_TO and MEMBER_OF are managed with Cypher in EmployeeQueries: mapping them
 * would make every load follow manager -> manager's department -> its head -> ...
 * and pull in the whole organisation. HAS_SKILL carries a level, so it is managed
 * there too.
 */
@Node("Employee")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Employee {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    @EqualsAndHashCode.Include
    @ToString.Include
    private String id;

    @ToString.Include
    private String employeeCode;

    @ToString.Include
    private String name;

    // Lower-case copy of name, indexed for case-insensitive prefix search. Kept in sync by setName.
    @Setter(AccessLevel.NONE)
    private String searchName;

    private String email;

    private String jobTitle;

    private LocalDate hireDate;

    private EmploymentStatus status = EmploymentStatus.ACTIVE;

    private LocalDate terminationDate;

    @Relationship(type = "WORKS_FOR")
    private Company company;

    public void setName(String name) {
        this.name = name;
        this.searchName = name == null ? null : name.toLowerCase(Locale.ROOT);
    }
}
