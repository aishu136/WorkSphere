package com.example.neo4j.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

/** An email waiting to be sent, written in the same transaction as the change that caused it. */
@Node("OutboxEmail")
@Getter
@Setter
public class OutboxEmail {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Claiming an email bumps the version, so two dispatchers can't send the same email at once.
    @Version
    private Long version;

    private String recipient;

    private String subject;

    private String body;

    // What caused it, e.g. "staffing-request:<id>:APPROVED". For tracing only.
    private String reference;

    private OutboxStatus status = OutboxStatus.PENDING;

    private int attempts;

    // When the dispatcher may next pick it up (also pushed forward while an attempt is in progress).
    private Instant nextAttemptAt;

    private String lastError;

    private Instant createdAt;

    private Instant sentAt;
}
