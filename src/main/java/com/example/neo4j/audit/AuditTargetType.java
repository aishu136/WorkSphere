package com.example.neo4j.audit;

/**
 * What an audit event is about. The target id is the record's id, except for USER, where it
 * is the username (usernames are unique and cannot be changed).
 * Project membership changes are recorded on the PROJECT, with the employeeId in the details.
 */
public enum AuditTargetType {

    EMPLOYEE,

    DEPARTMENT,

    OFFICE,

    PROJECT,

    USER
}
