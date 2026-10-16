package com.example.neo4j.dto;

import java.util.List;

/**
 * Skills a department's open projects need, against the active people in the department (or its
 * sub-departments) who have them. requiredLevel is the highest level any of those projects needs.
 * A skill is short when fewer qualified people are available than projects need it.
 */
public record DepartmentSkillGapResponse(
        DepartmentSummary department,
        int skillsShort,
        // By name.
        List<SkillSupply> skills) {

    public record SkillSupply(String skill, int requiredLevel, int projectsNeedingIt, int qualifiedPeople,
                              boolean shortage) {
    }
}
