package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.date;
import static com.example.neo4j.repository.EmployeeQueries.hasText;
import static com.example.neo4j.repository.EmployeeQueries.skillList;
import static com.example.neo4j.repository.EmployeeQueries.text;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import com.example.neo4j.dto.CandidateResponse.SkillMatch;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeAssignmentResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.Skill;

/**
 * Hand-written Cypher for projects: reads, and the WORKS_ON (members, with role and
 * allocationPercent), LED_BY (lead) and OWNED_BY (department) relationships.
 */
@Repository
public class ProjectQueries {

    private static final String OPEN = ProjectStatus.OPEN_CYPHER_LIST;

    private static final String PROJECTION = """
            RETURN p {.id, .code, .name, .description, .status, .startDate, .endDate} AS project,
                   head([(p)-[:OWNED_BY]->(d:Department) | d {.id, .code, .name}]) AS department,
                   head([(p)-[:LED_BY]->(l:Employee) | l {.id, .name, .jobTitle}]) AS lead,
                   COUNT { (:Employee)-[:WORKS_ON]->(p) } AS memberCount,
                   [(p)-[r:REQUIRES]->(s:Skill) | s {.id, .name, level: r.level}] AS requiredSkills
            ORDER BY project.name, project.id
            """;

    /*
     * Candidates for a project: active employees who aren't on it yet and have at least one of its
     * required skills, with how well they match, plus their bookings and leave in the window
     * [$from, $to] so ProjectService can work out free capacity and availability.
     * The required skills are collected by name first, so every candidate's skill list comes out
     * in the same order. The project is "target", not "p", so it can't clash with the p of the
     * bookings comprehension.
     * Scoring: full credit for meeting a required level, partial credit (level / required) below
     * it, none without the skill.
     */
    private static final String CANDIDATES = """
            MATCH (target:Project {id: $id})-[req:REQUIRES]->(s:Skill)
            WITH target, req, s ORDER BY toLower(s.name)
            WITH target, collect({id: s.id, name: s.name, level: req.level}) AS required
            MATCH (e:Employee {status: 'ACTIVE'})-[:HAS_SKILL]->(:Skill)<-[:REQUIRES]-(target)
            WHERE NOT EXISTS { (e)-[:WORKS_ON]->(target) }
            WITH DISTINCT target, required, e
            WITH target, e,
                 [r IN required | {name: r.name, required: r.level,
                     level: head([(e)-[h:HAS_SKILL]->(:Skill {id: r.id}) | coalesce(h.level, %d)])}] AS skills
            RETURN e {.id, .name, .jobTitle} AS employee, skills,
                   reduce(score = 0.0, x IN skills | score + CASE
                       WHEN x.level IS NULL THEN 0.0
                       WHEN x.level >= x.required THEN 1.0
                       ELSE toFloat(x.level) / x.required END) / size(skills) AS score,
                   EXISTS { (e)-[:MEMBER_OF]->(:Department)-[:PART_OF*0..]->(:Department)<-[:OWNED_BY]-(target) }
                       AS inOwningDepartment,
                   [(e)-[w:WORKS_ON]->(p:Project)
                       WHERE p.status IN %s AND w.startDate <= $to AND (w.endDate IS NULL OR w.endDate >= $from)
                       | {startDate: w.startDate, endDate: w.endDate, allocationPercent: w.allocationPercent}]
                       AS bookings,
                   [(e)-[:LOCATED_AT]->(:Office)-[:OBSERVES]->(h:Holiday) WHERE h.date >= $from AND h.date <= $to
                       | h.date] AS holidays,
                   COLLECT {
                       MATCH (l:LeaveRequest {employeeId: e.id})
                       WHERE l.status IN ['APPROVED', 'PENDING'] AND l.startDate <= $to AND l.endDate >= $from
                       RETURN {startDate: l.startDate, endDate: l.endDate, status: l.status}
                   } AS leave
            """.formatted(Skill.DEFAULT_LEVEL, ProjectStatus.OPEN_CYPHER_LIST);

    /** One assignment on an open project: allocationPercent from startDate to endDate (null: open-ended). */
    public record Booking(LocalDate startDate, LocalDate endDate, int allocationPercent) {
    }

    /** A member of a project and their booking on it. */
    public record MemberBooking(String employeeId, String name, Booking booking) {
    }

    /** Someone who could join a project, before their capacity and leave in the window are worked out. */
    public record CandidateRow(EmployeeSummary employee, double score, List<SkillMatch> skills,
                               boolean inOwningDepartment, List<Booking> bookings, List<LeaveQueries.Period> leave,
                               Set<LocalDate> holidays) {
    }

    private final Neo4jClient client;

    public ProjectQueries(Neo4jClient client) {
        this.client = client;
    }

    // ---- Reads ------------------------------------------------------------

    public Optional<ProjectResponse> findById(String id) {
        return client.query("MATCH (p:Project {id: $id}) " + PROJECTION)
                .bind(id).to("id")
                .fetchAs(ProjectResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    /** Optional filters: name (case-insensitive, contains), status, owning department. */
    public Page<ProjectResponse> search(String name, ProjectStatus status, String departmentId, Pageable pageable) {

        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (hasText(name)) {
            conditions.add("toLower(p.name) CONTAINS $name");
            params.put("name", name.trim().toLowerCase());
        }
        if (status != null) {
            conditions.add("p.status = $status");
            params.put("status", status.name());
        }
        if (hasText(departmentId)) {
            conditions.add("EXISTS { (p)-[:OWNED_BY]->(:Department {id: $departmentId}) }");
            params.put("departmentId", departmentId);
        }

        String match = "MATCH (p:Project)"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        long total = client.query(match + " RETURN count(p) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<ProjectResponse> content = List.copyOf(client.query(match
                        + " WITH p ORDER BY p.name, p.id SKIP $skip LIMIT $limit " + PROJECTION)
                .bindAll(pageParams)
                .fetchAs(ProjectResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    private static final String MEMBER_PROJECTION = """
             RETURN e {.id, .name, .jobTitle} AS employee, w.role AS role,
                    w.allocationPercent AS allocationPercent, w.since AS since,
                    w.startDate AS startDate, w.endDate AS endDate
            """;

    public Page<ProjectMemberResponse> members(String projectId, Pageable pageable) {

        String match = "MATCH (e:Employee)-[w:WORKS_ON]->(:Project {id: $id})";

        long total = client.query(match + " RETURN count(w) AS total")
                .bind(projectId).to("id")
                .fetchAs(Long.class).one().orElse(0L);

        List<ProjectMemberResponse> content = List.copyOf(client.query(match + MEMBER_PROJECTION
                        + " ORDER BY e.name, e.id SKIP $skip LIMIT $limit")
                .bindAll(Map.of("id", projectId, "skip", pageable.getOffset(), "limit", pageable.getPageSize()))
                .fetchAs(ProjectMemberResponse.class)
                .mappedBy((types, record) -> toMember(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    /** One person's assignment on a project, if they are on it. */
    public Optional<ProjectMemberResponse> member(String projectId, String employeeId) {
        return client.query("MATCH (e:Employee {id: $employeeId})-[w:WORKS_ON]->(:Project {id: $id})"
                        + MEMBER_PROJECTION)
                .bindAll(Map.of("id", projectId, "employeeId", employeeId))
                .fetchAs(ProjectMemberResponse.class)
                .mappedBy((types, record) -> toMember(record))
                .one();
    }

    /** All of an employee's projects, open ones first, then by name. */
    public List<EmployeeAssignmentResponse> assignmentsOf(String employeeId) {
        return List.copyOf(client.query("""
                        MATCH (:Employee {id: $id})-[w:WORKS_ON]->(p:Project)
                        RETURN p {.id, .code, .name, .status} AS project, w.role AS role,
                               w.allocationPercent AS allocationPercent, w.since AS since,
                               w.startDate AS startDate, w.endDate AS endDate,
                               p.status IN %s AS open
                        ORDER BY open DESC, p.name, p.id
                        """.formatted(OPEN))
                .bind(employeeId).to("id")
                .fetchAs(EmployeeAssignmentResponse.class)
                .mappedBy((types, record) -> {
                    Value p = record.get("project");
                    return new EmployeeAssignmentResponse(
                            new ProjectSummary(text(p.get("id")), text(p.get("code")), text(p.get("name")),
                                    ProjectStatus.valueOf(text(p.get("status")))),
                            text(record.get("role")),
                            record.get("allocationPercent").asInt(),
                            date(record.get("since")),
                            date(record.get("startDate")),
                            date(record.get("endDate")));
                })
                .all());
    }

    /** The employee's bookings on open projects, not counting the given project. */
    public List<Booking> bookingsExcluding(String employeeId, String projectId) {
        return List.copyOf(client.query("""
                        MATCH (:Employee {id: $id})-[w:WORKS_ON]->(p:Project)
                        WHERE p.status IN %s AND p.id <> $projectId
                        RETURN w.startDate AS startDate, w.endDate AS endDate, w.allocationPercent AS allocationPercent
                        """.formatted(OPEN))
                .bindAll(Map.of("id", employeeId, "projectId", projectId))
                .fetchAs(Booking.class)
                .mappedBy((types, record) -> toBooking(record))
                .all());
    }

    /** Everyone on the project with their booking on it, by name. */
    public List<MemberBooking> memberBookings(String projectId) {
        return List.copyOf(client.query("""
                        MATCH (e:Employee)-[w:WORKS_ON]->(:Project {id: $id})
                        RETURN e.id AS employeeId, e.name AS name, w.startDate AS startDate, w.endDate AS endDate,
                               w.allocationPercent AS allocationPercent
                        ORDER BY name, employeeId
                        """)
                .bind(projectId).to("id")
                .fetchAs(MemberBooking.class)
                .mappedBy((types, record) -> new MemberBooking(text(record.get("employeeId")),
                        text(record.get("name")), toBooking(record)))
                .all());
    }

    /** Candidates for the project, with their bookings and leave between from and to. */
    public List<CandidateRow> candidates(String projectId, LocalDate from, LocalDate to) {
        return List.copyOf(client.query(CANDIDATES)
                .bindAll(Map.of("id", projectId, "from", from, "to", to))
                .fetchAs(CandidateRow.class)
                .mappedBy((types, record) -> toCandidate(record))
                .all());
    }

    public List<SkillResponse> requiredSkillsOf(String projectId) {
        return List.copyOf(client.query("""
                        MATCH (:Project {id: $id})-[r:REQUIRES]->(s:Skill)
                        RETURN s.id AS id, s.name AS name, r.level AS level
                        ORDER BY toLower(name)
                        """)
                .bind(projectId).to("id")
                .fetchAs(SkillResponse.class)
                .mappedBy((types, record) -> new SkillResponse(text(record.get("id")), text(record.get("name")),
                        record.get("level").asInt(Skill.DEFAULT_LEVEL)))
                .all());
    }

    public boolean hasMembers(String projectId) {
        return client.query("RETURN EXISTS { (:Employee)-[:WORKS_ON]->(:Project {id: $id}) } AS inUse")
                .bind(projectId).to("id")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    /**
     * Authorization check: is this login the project's lead? False if the login is disabled,
     * not linked to an employee, or that employee is terminated.
     */
    public boolean isLedBy(String username, String projectId) {
        return client.query("""
                        MATCH (u:AppUser {username: $username})
                        WHERE coalesce(u.enabled, true)
                        RETURN EXISTS {
                            MATCH (:Project {id: $projectId})-[:LED_BY]->(me:Employee {id: u.employeeId})
                            WHERE me.status <> 'TERMINATED'
                        } AS leads
                        """)
                .bindAll(Map.of("username", username, "projectId", projectId))
                .fetchAs(Boolean.class).one().orElse(false);
    }

    public boolean leadsOpenProject(String employeeId) {
        return client.query("""
                        RETURN EXISTS { (p:Project)-[:LED_BY]->(:Employee {id: $id}) WHERE p.status IN %s } AS leads
                        """.formatted(OPEN))
                .bind(employeeId).to("id")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    // ---- Relationship writes ----------------------------------------------

    /**
     * Adds the employee to the project, or updates their role, allocation and dates if already on it.
     * The join date comes from the application's clock, like every other date in the app;
     * Cypher's date() would use the database's time zone instead.
     */
    public void assign(String employeeId, String projectId, String role, int allocationPercent,
                       LocalDate startDate, LocalDate endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", employeeId);
        params.put("projectId", projectId);
        params.put("role", role);
        params.put("allocation", allocationPercent);
        params.put("startDate", startDate);
        params.put("endDate", endDate);
        params.put("today", LocalDate.now());

        client.query("""
                        MATCH (e:Employee {id: $id}), (p:Project {id: $projectId})
                        MERGE (e)-[w:WORKS_ON]->(p)
                        ON CREATE SET w.since = $today
                        SET w.role = $role, w.allocationPercent = $allocation,
                            w.startDate = $startDate, w.endDate = $endDate
                        """)
                .bindAll(params)
                .run();
    }

    public void unassign(String employeeId, String projectId) {
        client.query("MATCH (:Employee {id: $id})-[w:WORKS_ON]->(:Project {id: $projectId}) DELETE w")
                .bindAll(Map.of("id", employeeId, "projectId", projectId))
                .run();
    }

    /** Offboarding: removes the employee from open projects; finished ones are kept as history. */
    public List<String> removeOpenAssignments(String employeeId) {
        return List.copyOf(client.query("""
                        MATCH (:Employee {id: $id})-[w:WORKS_ON]->(p:Project)
                        WHERE p.status IN %s
                        WITH w, p.id AS projectId
                        DELETE w
                        RETURN projectId
                        """.formatted(OPEN))
                .bind(employeeId).to("id")
                .fetchAs(String.class).all());
    }

    // Adds the required skill, or changes its minimum level if the project already requires it.
    public void setRequiredSkill(String projectId, String skillId, int level) {
        client.query("""
                        MATCH (p:Project {id: $id}), (s:Skill {id: $skillId})
                        MERGE (p)-[r:REQUIRES]->(s)
                        SET r.level = $level
                        """)
                .bindAll(Map.of("id", projectId, "skillId", skillId, "level", level))
                .run();
    }

    public void removeRequiredSkills(String projectId, List<String> skillIds) {
        client.query("MATCH (:Project {id: $id})-[r:REQUIRES]->(s:Skill) WHERE s.id IN $skillIds DELETE r")
                .bindAll(Map.of("id", projectId, "skillIds", skillIds))
                .run();
    }

    public void setLead(String projectId, String employeeId) {
        replaceSingle(projectId, "LED_BY", "Employee", employeeId);
    }

    public void clearLead(String projectId) {
        client.query("MATCH (:Project {id: $id})-[r:LED_BY]->() DELETE r").bind(projectId).to("id").run();
    }

    public void setDepartment(String projectId, String departmentId) {
        replaceSingle(projectId, "OWNED_BY", "Department", departmentId);
    }

    public void clearDepartment(String projectId) {
        client.query("MATCH (:Project {id: $id})-[r:OWNED_BY]->() DELETE r").bind(projectId).to("id").run();
    }

    // relationshipType and targetLabel are fixed constants from this class, never user input.
    private void replaceSingle(String projectId, String relationshipType, String targetLabel, String targetId) {
        client.query("""
                        MATCH (p:Project {id: $id}), (t:%s {id: $targetId})
                        OPTIONAL MATCH (p)-[old:%s]->()
                        DELETE old
                        WITH DISTINCT p, t
                        CREATE (p)-[:%s]->(t)
                        """.formatted(targetLabel, relationshipType, relationshipType))
                .bindAll(Map.of("id", projectId, "targetId", targetId))
                .run();
    }

    // ---- Helpers ----------------------------------------------------------

    private static ProjectMemberResponse toMember(Record record) {
        Value e = record.get("employee");
        return new ProjectMemberResponse(
                new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle"))),
                text(record.get("role")),
                record.get("allocationPercent").asInt(),
                date(record.get("since")),
                date(record.get("startDate")),
                date(record.get("endDate")));
    }

    private static Booking toBooking(Record record) {
        return new Booking(date(record.get("startDate")), date(record.get("endDate")),
                record.get("allocationPercent").asInt());
    }

    private static ProjectResponse toResponse(Record record) {
        Value p = record.get("project");
        Value department = record.get("department");
        Value lead = record.get("lead");

        return new ProjectResponse(
                text(p.get("id")),
                text(p.get("code")),
                text(p.get("name")),
                text(p.get("description")),
                ProjectStatus.valueOf(text(p.get("status"))),
                date(p.get("startDate")),
                date(p.get("endDate")),
                department.isNull() ? null
                        : new DepartmentSummary(text(department.get("id")), text(department.get("code")),
                                text(department.get("name"))),
                lead.isNull() ? null
                        : new EmployeeSummary(text(lead.get("id")), text(lead.get("name")), text(lead.get("jobTitle"))),
                record.get("memberCount").asLong(),
                skillList(record.get("requiredSkills")));
    }

    private static CandidateRow toCandidate(Record record) {
        Value e = record.get("employee");
        List<SkillMatch> skills = record.get("skills").asList(s -> {
            Integer level = s.get("level").isNull() ? null : s.get("level").asInt();
            int required = s.get("required").asInt();
            return new SkillMatch(text(s.get("name")), required, level, level != null && level >= required);
        });
        List<Booking> bookings = record.get("bookings").asList(b -> new Booking(date(b.get("startDate")),
                date(b.get("endDate")), b.get("allocationPercent").asInt()));
        List<LeaveQueries.Period> leave = record.get("leave").asList(l -> new LeaveQueries.Period(
                date(l.get("startDate")), date(l.get("endDate")), LeaveStatus.valueOf(text(l.get("status")))));

        return new CandidateRow(
                new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle"))),
                record.get("score").asDouble(),
                skills,
                record.get("inOwningDepartment").asBoolean(),
                bookings,
                leave,
                Set.copyOf(record.get("holidays").asList(EmployeeQueries::date)));
    }
}
