package com.example.neo4j.outbox;

import java.time.Instant;

// For the admin view. The body is left out: it can contain personal details and isn't needed to fix delivery.
public record OutboxEmailResponse(
        String id,
        String recipient,
        String subject,
        String reference,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant sentAt) {

    static OutboxEmailResponse from(OutboxEmail email) {
        return new OutboxEmailResponse(email.getId(), email.getRecipient(), email.getSubject(), email.getReference(),
                email.getStatus(), email.getAttempts(), email.getNextAttemptAt(), email.getLastError(),
                email.getCreatedAt(), email.getSentAt());
    }
}
