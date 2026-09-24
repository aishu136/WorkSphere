package com.example.neo4j.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Append-only audit trail stored as (:AuditEvent) nodes.
 *
 * Services call record() inside their own @Transactional method, so the event commits or
 * rolls back together with the change it describes. There is intentionally no repository
 * and no update/delete method: history can be added to, never rewritten.
 */
@Service
public class AuditLog {

    public static final String SYSTEM_ACTOR = CurrentUser.SYSTEM;

    // Fixed settings so the stored format never changes with the application's JSON config.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    // Orders events that share a timestamp (several per transaction are common): microseconds
    // since the epoch, but always at least one more than the previous event from this instance.
    private static final AtomicLong LAST_SEQUENCE = new AtomicLong();

    private final Neo4jClient client;
    private final Clock clock = Clock.systemUTC();

    public AuditLog(Neo4jClient client) {
        this.client = client;
    }

    /** Records an action by the currently logged-in user ("system" when there is none). */
    @Transactional
    public void record(AuditAction action, AuditTargetType targetType, String targetId, Map<String, Object> details) {
        recordAs(CurrentUser.username(), action, targetType, targetId, details);
    }

    /** Records an action on behalf of an explicit actor, e.g. the username on a login attempt. */
    @Transactional
    public void recordAs(String actor, AuditAction action, AuditTargetType targetType, String targetId,
                         Map<String, Object> details) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID().toString());
        Instant now = clock.instant();
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, now);
        params.put("seq", LAST_SEQUENCE.updateAndGet(last -> Math.max(last + 1, micros)));
        params.put("timestamp", OffsetDateTime.ofInstant(now, clock.getZone()).truncatedTo(ChronoUnit.MILLIS));
        params.put("actor", actor);
        params.put("action", action.name());
        params.put("targetType", targetType.name());
        params.put("targetId", targetId);
        params.put("details", toJson(details));

        client.query("""
                        CREATE (:AuditEvent {id: $id, seq: $seq, timestamp: $timestamp, actor: $actor, action: $action,
                                             targetType: $targetType, targetId: $targetId, details: $details})
                        """)
                .bindAll(params)
                .run();
    }

    /** Newest first. Only the filters that are present are added to the query. */
    @Transactional(readOnly = true)
    public Page<AuditEventResponse> search(AuditFilter filter, Pageable pageable) {

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (filter.actor() != null && !filter.actor().isBlank()) {
            conditions.add("a.actor = $actor");
            params.put("actor", filter.actor().trim());
        }
        if (filter.action() != null) {
            conditions.add("a.action = $action");
            params.put("action", filter.action().name());
        }
        if (filter.targetType() != null) {
            conditions.add("a.targetType = $targetType");
            params.put("targetType", filter.targetType().name());
        }
        if (filter.targetId() != null && !filter.targetId().isBlank()) {
            conditions.add("a.targetId = $targetId");
            params.put("targetId", filter.targetId().trim());
        }
        if (filter.from() != null) {
            conditions.add("a.timestamp >= $from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            conditions.add("a.timestamp < $to");
            params.put("to", filter.to());
        }

        String match = "MATCH (a:AuditEvent)"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        long total = client.query(match + " RETURN count(a) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<AuditEventResponse> content = List.copyOf(client.query(match + """
                         RETURN a {.*} AS event
                         ORDER BY a.seq DESC, a.id DESC
                         SKIP $skip LIMIT $limit
                        """)
                .bindAll(pageParams)
                .fetchAs(AuditEventResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    private static AuditEventResponse toResponse(Record record) {
        Value event = record.get("event");
        return new AuditEventResponse(
                event.get("id").asString(),
                event.get("timestamp").asOffsetDateTime(),
                event.get("actor").asString(),
                AuditAction.valueOf(event.get("action").asString()),
                AuditTargetType.valueOf(event.get("targetType").asString()),
                event.get("targetId").isNull() ? null : event.get("targetId").asString(),
                fromJson(event.get("details").isNull() ? null : event.get("details").asString()));
    }

    private static String toJson(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize audit details", e);
        }
    }

    private static Map<String, Object> fromJson(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read audit details", e);
        }
    }
}
