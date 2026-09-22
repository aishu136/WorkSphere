package com.example.neo4j.audit;

/**
 * What an audit event is about. The target id is the record's id for EMPLOYEE and
 * DEPARTMENT, and the username for USER (usernames are unique and cannot be changed).
 */
public enum AuditTargetType {

    EMPLOYEE,

    DEPARTMENT,

    USER
}
