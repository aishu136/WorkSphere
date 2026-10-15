package com.example.neo4j.entity;

public enum LeaveStatus {

    /** Waiting for a manager or HR. */
    PENDING,

    /** A manager or HR approved it. */
    APPROVED,

    /** A manager or HR rejected it, with a reason. */
    REJECTED,

    /** The employee withdrew it, or it was cancelled when they were terminated. */
    CANCELLED
}
