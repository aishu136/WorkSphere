package com.example.neo4j.dto;

import com.example.neo4j.entity.EmploymentStatus;

import jakarta.validation.constraints.Size;

/**
 * Optional directory filters from query parameters, e.g.
 * /employees?name=pri&status=ACTIVE&departmentId=...&skill=java&company=acme
 */
public record EmployeeFilter(

        // Case-insensitive name prefix.
        @Size(max = 100)
        String name,

        EmploymentStatus status,

        String departmentId,

        // Case-insensitive exact skill name.
        @Size(max = 100)
        String skill,

        // Case-insensitive exact company name.
        @Size(max = 100)
        String company) {

    public static EmployeeFilter byName(String name) {
        return new EmployeeFilter(name, null, null, null, null);
    }
}
