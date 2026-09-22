package com.example.neo4j.security;

public enum Role {

    /** Full access, including user management. */
    ADMIN,

    /** Can create, change and delete employee records. */
    HR,

    /** Read-only access to the directory and the AI assistant. */
    EMPLOYEE
}
