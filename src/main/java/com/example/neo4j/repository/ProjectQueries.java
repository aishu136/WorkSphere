package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.date;
import static com.example.neo4j.repository.EmployeeQueries.hasText;
import static com.example.neo4j.repository.EmployeeQueries.text;

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

import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeAssignmentResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.entity.ProjectStatus;

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
                   COUNT { (:Employee)-[:WORKS_ON]->(p) } AS memberCount
            ORDER BY project.name, project.id
            """;

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
                    w.allocationPercent AS allocationPercent, w.since AS since
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
                            date(record.get("since")));
                })
                .all());
    }

    /** The employee's allocation on open projects, not counting the given project. */
    public int openAllocationExcluding(String employeeId, String projectId) {
        return client.query("""
                        MATCH (e:Employee {id: $id})
                        RETURN reduce(total = 0, a IN [(e)-[w:WORKS_ON]->(p:Project)
                                   WHERE p.status IN %s AND p.id <> $projectId | w.allocationPercent]
                               | total + a) AS allocation
                        """.formatted(OPEN))
                .bindAll(Map.of("id", employeeId, "projectId", projectId))
                .fetchAs(Integer.class).one().orElse(0);
    }

    /** Members who would go over 100% if this project counted toward their allocation again. */
    public List<String> membersOverAllocatedIfReopened(String projectId) {
        return List.copyOf(client.query("""
                        MATCH (e:Employee)-[w:WORKS_ON]->(:Project {id: $id})
                        WITH e, w, reduce(total = 0, a IN [(e)-[o:WORKS_ON]->(p:Project)
                                   WHERE p.status IN %s AND p.id <> $id | o.allocationPercent]
                               | total + a) AS elsewhere
                        WHERE elsewhere + w.allocationPercent > 100
                        RETURN e.name AS name
                        ORDER BY name
                        """.formatted(OPEN))
                .bind(projectId).to("id")
                .fetchAs(String.class).all());
    }

    public boolean hasMembers(String projectId) {
        return client.query("RETURN EXISTS { (:Employee)-[:WORKS_ON]->(:Project {id: $id}) } AS inUse")
                .bind(projectId).to("id")
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

    /** Adds the employee to the project, or updates their role and allocation if already on it. */
    public void assign(String employeeId, String projectId, String role, int allocationPercent) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", employeeId);
        params.put("projectId", projectId);
        params.put("role", role);
        params.put("allocation", allocationPercent);

        client.query("""
                        MATCH (e:Employee {id: $id}), (p:Project {id: $projectId})
                        MERGE (e)-[w:WORKS_ON]->(p)
                        ON CREATE SET w.since = date()
                        SET w.role = $role, w.allocationPercent = $allocation
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
                date(record.get("since")));
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
                record.get("memberCount").asLong());
    }
}
