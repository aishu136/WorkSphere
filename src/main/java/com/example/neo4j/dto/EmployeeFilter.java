package com.example.neo4j.dto;

import com.example.neo4j.entity.EmploymentStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Optional directory filters from query parameters, e.g.
 * /employees?name=pri&status=ACTIVE&departmentId=...&skill=java&company=acme&officeId=...&maxAllocation=50
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
        String company,

        String officeId,

        // Availability: only people whose allocation on open projects is at most this, 0-100.
        @Min(0)
        @Max(100)
        Integer maxAllocation) {

    public static EmployeeFilter byName(String name) {
        return new EmployeeFilter(name, null, null, null, null, null, null);
    }
}
