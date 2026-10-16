package com.example.neo4j.dto;

import java.time.OffsetDateTime;

/**
 * A stretch of time someone had a manager or was in a department. from is null when it started
 * before the audit trail has records; to is null while it is still the case. name is the current
 * name, or null if that record no longer exists.
 */
public record HistoryPeriod(String id, String name, OffsetDateTime from, OffsetDateTime to) {
}
