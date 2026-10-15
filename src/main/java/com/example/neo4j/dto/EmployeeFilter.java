package com.example.neo4j.dto;

import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.Skill;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Optional directory filters from query parameters, e.g.
 * /employees?name=pri&status=ACTIVE&departmentId=...&skill=java&minSkillLevel=4&company=acme&officeId=...&maxAllocation=50
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

        // Only people with the skill at this level or above, 1-5. Needs skill.
        @Min(Skill.MIN_LEVEL)
        @Max(Skill.MAX_LEVEL)
        Integer minSkillLevel,

        // Case-insensitive exact company name.
        @Size(max = 100)
        String company,

        String officeId,

        // Availability: only people whose allocation on open projects is at most this, 0-100.
        @Min(0)
        @Max(100)
        Integer maxAllocation) {

    public static EmployeeFilter byName(String name) {
        return new EmployeeFilter(name, null, null, null, null, null, null, null);
    }

    public static EmployeeFilter byOffice(String officeId) {
        return new EmployeeFilter(null, null, null, null, null, null, officeId, null);
    }
}
