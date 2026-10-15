package com.example.neo4j.entity;

public enum LeaveType {

    /** Paid holiday. Counts against the yearly allowance. */
    ANNUAL,

    SICK,

    UNPAID,

    OTHER;

    public boolean countsAgainstAllowance() {
        return this == ANNUAL;
    }
}
