package com.example.neo4j.notification;

/** Published by LeaveService at each step of the leave workflow. */
public record LeaveRequestEvent(String requestId, Type type) {

    public enum Type {
        REQUESTED,
        APPROVED,
        REJECTED,
        CANCELLED
    }
}
