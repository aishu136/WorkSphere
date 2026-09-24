package com.example.neo4j.notification;

import java.util.Map;

/**
 * Published by EmployeeService when an employee's status, skills or manager actually changes.
 *
 * @param details    what changed, e.g. {"status": {"from": "ACTIVE", "to": "ON_LEAVE"}}
 * @param actor      username of whoever made the change
 * @param selfService true when a manager (not HR/ADMIN) made it
 */
public record EmployeeChangeEvent(String employeeId, Type type, Map<String, Object> details, String actor,
                                  boolean selfService) {

    public enum Type {
        STATUS_CHANGED,
        SKILLS_CHANGED,
        MANAGER_CHANGED
    }
}
