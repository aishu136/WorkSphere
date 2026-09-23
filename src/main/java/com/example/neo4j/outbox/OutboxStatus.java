package com.example.neo4j.outbox;

public enum OutboxStatus {

    /** Waiting to be sent, or waiting for its next retry. */
    PENDING,

    SENT,

    /** Gave up after the maximum number of attempts. An admin can retry it. */
    FAILED
}
