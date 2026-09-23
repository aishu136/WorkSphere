package com.example.neo4j.entity;

public enum StaffingRequestStatus {

    /** Waiting for HR. */
    PENDING,

    /** HR approved it and the change was applied. */
    APPROVED,

    /** HR rejected it, with a reason. Nothing changed. */
    REJECTED,

    /** The requester withdrew it. Nothing changed. */
    CANCELLED
}
