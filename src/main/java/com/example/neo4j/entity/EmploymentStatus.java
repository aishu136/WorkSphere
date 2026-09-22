package com.example.neo4j.entity;

public enum EmploymentStatus {

    ACTIVE,

    ON_LEAVE,

    /** Offboarded. Set by DELETE /employees/{id}; the record is kept, not deleted. */
    TERMINATED
}
