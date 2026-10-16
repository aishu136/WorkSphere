package com.example.neo4j.dto;

import java.util.List;

/**
 * An open project's "bus factor": for each required skill, which current members have it at the
 * required level. A skill is at risk when one person or nobody covers it.
 */
public record ProjectSkillRiskResponse(
        ProjectSummary project,
        int skillsAtRisk,
        // By name.
        List<SkillCoverage> skills) {

    public record SkillCoverage(String skill, int requiredLevel, List<EmployeeSummary> coveredBy, boolean atRisk) {
    }
}
