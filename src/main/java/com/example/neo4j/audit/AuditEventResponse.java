package com.example.neo4j.audit;

import java.time.OffsetDateTime;
import java.util.Map;

public record AuditEventResponse(
        String id,
        OffsetDateTime timestamp,
        String actor,
        AuditAction action,
        AuditTargetType targetType,
        String targetId,
        Map<String, Object> details) {
}
