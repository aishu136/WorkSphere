package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.date;
import static com.example.neo4j.repository.EmployeeQueries.hasText;
import static com.example.neo4j.repository.EmployeeQueries.text;
import static com.example.neo4j.repository.StaffingQueries.instant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.example.neo4j.dto.LeaveFilter;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.LeaveType;

/** Reads leave requests with their employee, by start date, and the bulk changes termination needs. */
@Repository
public class LeaveQueries {

    private static final String PROJECTION = """
            OPTIONAL MATCH (e:Employee {id: r.employeeId})
            RETURN r {.*} AS request,
                   CASE WHEN e IS NULL THEN null ELSE e {.id, .name, .jobTitle} END AS employee
            ORDER BY request.startDate, request.requestedAt, request.id
            """;

    /** The dates and status of one leave request, for counting days. */
    public record Period(LocalDate startDate, LocalDate endDate, LeaveStatus status) {
    }

    private final Neo4jClient client;

    public LeaveQueries(Neo4jClient client) {
        this.client = client;
    }

    public Optional<LeaveRequestResponse> findById(String id) {
        return client.query("MATCH (r:LeaveRequest {id: $id}) " + PROJECTION)
                .bind(id).to("id")
                .fetchAs(LeaveRequestResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    /**
     * Requests matching the filter. With teamOfEmployeeId, only requests of people below that
     * employee in the org chart (at any depth).
     */
    public Page<LeaveRequestResponse> search(LeaveFilter filter, String teamOfEmployeeId, Pageable pageable) {

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (hasText(filter.employeeId())) {
            conditions.add("r.employeeId = $employeeId");
            params.put("employeeId", filter.employeeId());
        }
        if (filter.status() != null) {
            conditions.add("r.status = $status");
            params.put("status", filter.status().name());
        }
        if (filter.from() != null) {
            conditions.add("r.endDate >= $from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            conditions.add("r.startDate <= $to");
            params.put("to", filter.to());
        }
        if (hasText(teamOfEmployeeId)) {
            conditions.add("EXISTS { (:Employee {id: r.employeeId})-[:REPORTS_TO*1..]->(:Employee {id: $managerId}) }");
            params.put("managerId", teamOfEmployeeId);
        }

        String match = "MATCH (r:LeaveRequest)"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        long total = client.query(match + " RETURN count(r) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<LeaveRequestResponse> content = List.copyOf(client.query(match
                        + " WITH r ORDER BY r.startDate, r.requestedAt, r.id SKIP $skip LIMIT $limit " + PROJECTION)
                .bindAll(pageParams)
                .fetchAs(LeaveRequestResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    /** True if the employee has pending or approved leave on any of these days, apart from excludeId. */
    public boolean overlapsOpenLeave(String employeeId, LocalDate start, LocalDate end, String excludeId) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("start", start);
        params.put("end", end);
        params.put("excludeId", excludeId);

        return client.query("""
                        RETURN EXISTS {
                            MATCH (r:LeaveRequest {employeeId: $employeeId})
                            WHERE r.status IN ['PENDING', 'APPROVED']
                              AND r.startDate <= $end AND r.endDate >= $start
                              AND ($excludeId IS NULL OR r.id <> $excludeId)
                        } AS overlaps
                        """)
                .bindAll(params)
                .fetchAs(Boolean.class).one().orElse(false);
    }

    /** The employee's requests of this type and status that overlap the dates. */
    public List<Period> periods(String employeeId, LeaveType type, List<LeaveStatus> statuses,
                                LocalDate from, LocalDate to) {
        return List.copyOf(client.query("""
                        MATCH (r:LeaveRequest {employeeId: $employeeId, type: $type})
                        WHERE r.status IN $statuses AND r.startDate <= $to AND r.endDate >= $from
                        RETURN r.startDate AS startDate, r.endDate AS endDate, r.status AS status
                        """)
                .bindAll(Map.of("employeeId", employeeId, "type", type.name(),
                        "statuses", statuses.stream().map(LeaveStatus::name).toList(), "from", from, "to", to))
                .fetchAs(Period.class)
                .mappedBy((types, record) -> new Period(date(record.get("startDate")), date(record.get("endDate")),
                        LeaveStatus.valueOf(text(record.get("status")))))
                .all());
    }

    /**
     * Takes a write lock on the employee until the transaction ends, so two requests for the same
     * person are checked for overlap and allowance one after the other, never side by side.
     */
    public void lockEmployee(String employeeId) {
        client.query("MATCH (e:Employee {id: $id}) SET e._lock = true REMOVE e._lock")
                .bind(employeeId).to("id")
                .run();
    }

    /**
     * Offboarding: cancels pending requests, and approved ones that haven't started yet.
     * Leave already taken stays as history. Returns the ids of the cancelled requests.
     */
    public List<String> cancelOpenRequests(String employeeId, String actor, Instant now, LocalDate today) {
        Map<String, Object> params = new HashMap<>();
        params.put("employeeId", employeeId);
        params.put("actor", actor);
        params.put("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        params.put("today", today);

        return List.copyOf(client.query("""
                        MATCH (r:LeaveRequest {employeeId: $employeeId})
                        WHERE r.status = 'PENDING' OR (r.status = 'APPROVED' AND r.startDate > $today)
                        SET r.status = 'CANCELLED', r.decidedBy = $actor, r.decidedAt = $now,
                            r.reason = 'Employee terminated', r.version = coalesce(r.version, 0) + 1
                        RETURN r.id AS id ORDER BY id
                        """)
                .bindAll(params)
                .fetchAs(String.class).all());
    }

    private static LeaveRequestResponse toResponse(Record record) {
        Value r = record.get("request");
        Value e = record.get("employee");

        return new LeaveRequestResponse(
                text(r.get("id")),
                e.isNull() ? null
                        : new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle"))),
                LeaveType.valueOf(text(r.get("type"))),
                date(r.get("startDate")),
                date(r.get("endDate")),
                r.get("workingDays").asInt(0),
                text(r.get("note")),
                LeaveStatus.valueOf(text(r.get("status"))),
                text(r.get("requestedBy")),
                instant(r.get("requestedAt")),
                text(r.get("decidedBy")),
                instant(r.get("decidedAt")),
                text(r.get("reason")));
    }
}
