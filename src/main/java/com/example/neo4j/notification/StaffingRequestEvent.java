package com.example.neo4j.notification;

/** Published by StaffingService at each step of the staffing workflow. */
public record StaffingRequestEvent(String requestId, Type type) {

    public enum Type {
        REQUESTED,
        APPROVED,
        REJECTED,
        CANCELLED
    }
}
