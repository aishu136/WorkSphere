package com.example.neo4j.dto;

import java.util.List;

/**
 * Someone who could join a project, and how well they fit its required skills and dates.
 *
 * matchPercent averages the required skills: full credit for meeting the level, partial credit
 * (their level / required level) for being below it, none for not having the skill.
 * Capacity and leave are for the window the candidates were looked up for.
 */
public record CandidateResponse(
        EmployeeSummary employee,
        int matchPercent,
        // 100 minus their allocation on their busiest day in the window.
        int freeCapacityPercent,
        // Working days in the window they are on approved leave, and on leave waiting for approval.
        int leaveDays,
        int pendingLeaveDays,
        // Share of the window's working days they are not on approved leave.
        int availabilityPercent,
        // In the project's owning department or one of its sub-departments.
        boolean inOwningDepartment,
        // One entry per required skill, by name.
        List<SkillMatch> skills) {

    // level is null when the person doesn't have the skill.
    public record SkillMatch(String skill, int requiredLevel, Integer level, boolean meetsLevel) {
    }
}
