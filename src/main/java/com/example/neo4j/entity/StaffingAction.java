package com.example.neo4j.entity;

public enum StaffingAction {

    /** Add the employee to the project, or change their role/allocation if already on it. */
    ASSIGN,

    /** Take the employee off the project. */
    REMOVE
}
