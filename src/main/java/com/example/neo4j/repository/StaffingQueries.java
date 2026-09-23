package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.hasText;
import static com.example.neo4j.repository.EmployeeQueries.text;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;

/** Reads staffing requests with their project and employee. Oldest first, so the HR queue is worked in order. */
@Repository
public class StaffingQueries {

    private static final String PROJECTION = """
            OPTIONAL MATCH (p:Project {id: r.projectId})
            OPTIONAL MATCH (e:Employee {id: r.employeeId})
            RETURN r {.*} AS request,
                   CASE WHEN p IS NULL THEN null ELSE p {.id, .code, .name, .status} END AS project,
                   CASE WHEN e IS NULL THEN null ELSE e {.id, .name, .jobTitle} END AS employee
            ORDER BY request.requestedAt, request.id
            """;

    private final Neo4jClient client;

    public StaffingQueries(Neo4jClient client) {
        this.client = client;
    }

    public Optional<StaffingRequestResponse> findById(String id) {
        return client.query("MATCH (r:StaffingRequest {id: $id}) " + PROJECTION)
                .bind(id).to("id")
                .fetchAs(StaffingRequestResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    public Page<StaffingRequestResponse> search(String projectId, StaffingRequestStatus status, Pageable pageable) {

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (hasText(projectId)) {
            conditions.add("r.projectId = $projectId");
            params.put("projectId", projectId);
        }
        if (status != null) {
            conditions.add("r.status = $status");
            params.put("status", status.name());
        }

        String match = "MATCH (r:StaffingRequest)"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        long total = client.query(match + " RETURN count(r) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<StaffingRequestResponse> content = List.copyOf(client.query(match
                        + " WITH r ORDER BY r.requestedAt, r.id SKIP $skip LIMIT $limit " + PROJECTION)
                .bindAll(pageParams)
                .fetchAs(StaffingRequestResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    private static StaffingRequestResponse toResponse(Record record) {
        Value r = record.get("request");
        Value p = record.get("project");
        Value e = record.get("employee");

        return new StaffingRequestResponse(
                text(r.get("id")),
                p.isNull() ? null
                        : new ProjectSummary(text(p.get("id")), text(p.get("code")), text(p.get("name")),
                                ProjectStatus.valueOf(text(p.get("status")))),
                e.isNull() ? null
                        : new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle"))),
                StaffingAction.valueOf(text(r.get("action"))),
                text(r.get("role")),
                r.get("allocationPercent").isNull() ? null : r.get("allocationPercent").asInt(),
                StaffingRequestStatus.valueOf(text(r.get("status"))),
                text(r.get("requestedBy")),
                instant(r.get("requestedAt")),
                text(r.get("decidedBy")),
                instant(r.get("decidedAt")),
                text(r.get("reason")));
    }

    // Written by Spring Data Neo4j from an Instant; accept any temporal form it may be stored in.
    private static Instant instant(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Object raw = value.asObject();
        if (raw instanceof ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (raw instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (raw instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("Unexpected timestamp type: " + raw.getClass());
    }
}
