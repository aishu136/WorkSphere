package com.example.neo4j.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import com.example.neo4j.dto.CompanyResponse;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.OfficeSummary;
import com.example.neo4j.dto.SkillCount;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.Skill;

/**
 * Hand-written Cypher for employee reads and for the org-chart relationships
 * (REPORTS_TO, MEMBER_OF) that are deliberately not mapped on the entity.
 *
 * Every query that returns a page orders by name, then id, so pages are stable.
 */
@Repository
public class EmployeeQueries {

    // Employee e's allocation today across open projects (finished projects, and assignments that
    // haven't started or have ended, don't count). Queries using it bind $today.
    static final String OPEN_ALLOCATION = "reduce(total = 0, a IN [(e)-[w:WORKS_ON]->(p:Project) "
            + "WHERE p.status IN " + ProjectStatus.OPEN_CYPHER_LIST
            + " AND w.startDate <= $today AND (w.endDate IS NULL OR w.endDate >= $today)"
            + " | w.allocationPercent] | total + a)";

    // Pattern comprehensions fetch related nodes without multiplying rows.
    private static final String EMPLOYEE_PROJECTION = """
            RETURN e {.id, .employeeCode, .name, .email, .jobTitle, .hireDate, .status, .terminationDate} AS employee,
                   head([(e)-[:MEMBER_OF]->(d:Department) | d {.id, .code, .name}]) AS department,
                   head([(e)-[:REPORTS_TO]->(m:Employee) | m {.id, .name, .jobTitle}]) AS manager,
                   head([(e)-[:WORKS_FOR]->(c:Company) | c {.id, .name}]) AS company,
                   head([(e)-[:LOCATED_AT]->(o:Office) | o {.id, .code, .name, .city}]) AS office,
                   [(e)-[r:HAS_SKILL]->(s:Skill) | s {.id, .name, level: r.level}] AS skills,
                   COUNT { (:Employee)-[:REPORTS_TO]->(e) } AS directReportCount,
                   %s AS allocationPercent
            ORDER BY employee.name, employee.id
            """.formatted(OPEN_ALLOCATION);

    private static final String SUMMARY_PROJECTION = """
            RETURN e {.id, .name, .jobTitle} AS employee
            ORDER BY employee.name, employee.id
            """;

    private final Neo4jClient client;

    public EmployeeQueries(Neo4jClient client) {
        this.client = client;
    }

    // ---- Reads ------------------------------------------------------------

    public Optional<EmployeeResponse> findById(String id) {
        return client.query("MATCH (e:Employee {id: $id}) " + EMPLOYEE_PROJECTION)
                .bindAll(Map.of("id", id, "today", LocalDate.now()))
                .fetchAs(EmployeeResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    public Page<EmployeeResponse> search(EmployeeFilter filter, Pageable pageable) {

        // Only the filters that are present are added, so the name index can be used.
        // Values are always bound as parameters, never concatenated into the query.
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (hasText(filter.name())) {
            conditions.add("e.searchName STARTS WITH $name");
            params.put("name", filter.name().trim().toLowerCase(Locale.ROOT));
        }
        if (filter.status() != null) {
            conditions.add("e.status = $status");
            params.put("status", filter.status().name());
        }
        if (hasText(filter.departmentId())) {
            conditions.add("EXISTS { (e)-[:MEMBER_OF]->(:Department {id: $departmentId}) }");
            params.put("departmentId", filter.departmentId());
        }
        if (hasText(filter.skill())) {
            conditions.add("EXISTS { (e)-[r:HAS_SKILL]->(s:Skill) WHERE toLower(s.name) = $skill "
                    + "AND r.level >= $minSkillLevel }");
            params.put("skill", filter.skill().trim().toLowerCase(Locale.ROOT));
            params.put("minSkillLevel", filter.minSkillLevel() == null ? Skill.MIN_LEVEL : filter.minSkillLevel());
        }
        if (hasText(filter.company())) {
            conditions.add("EXISTS { (e)-[:WORKS_FOR]->(c:Company) WHERE toLower(c.name) = $company }");
            params.put("company", filter.company().trim().toLowerCase(Locale.ROOT));
        }
        if (hasText(filter.officeId())) {
            conditions.add("EXISTS { (e)-[:LOCATED_AT]->(:Office {id: $officeId}) }");
            params.put("officeId", filter.officeId());
        }
        if (filter.maxAllocation() != null) {
            conditions.add(OPEN_ALLOCATION + " <= $maxAllocation");
            params.put("maxAllocation", filter.maxAllocation());
        }

        String match = "MATCH (e:Employee)"
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions));

        return page(match, params, pageable, EMPLOYEE_PROJECTION,
                EmployeeResponse.class, EmployeeQueries::toResponse);
    }

    public Page<EmployeeSummary> directReports(String managerId, Pageable pageable) {
        return page("MATCH (e:Employee)-[:REPORTS_TO]->(:Employee {id: $id})",
                Map.of("id", managerId), pageable, SUMMARY_PROJECTION,
                EmployeeSummary.class, EmployeeQueries::toSummary);
    }

    // Everyone below this manager, at any depth.
    public Page<EmployeeSummary> allReports(String managerId, Pageable pageable) {
        return page("MATCH (e:Employee)-[:REPORTS_TO*1..]->(:Employee {id: $id})",
                Map.of("id", managerId), pageable, SUMMARY_PROJECTION,
                EmployeeSummary.class, EmployeeQueries::toSummary);
    }

    // Direct manager first, up to the top of the organisation.
    public List<EmployeeSummary> reportingChain(String employeeId) {
        return new ArrayList<>(client.query("""
                        MATCH path = (:Employee {id: $id})-[:REPORTS_TO*1..]->(e:Employee)
                        RETURN e {.id, .name, .jobTitle} AS employee, length(path) AS level
                        ORDER BY level
                        """)
                .bind(employeeId).to("id")
                .fetchAs(EmployeeSummary.class)
                .mappedBy((types, record) -> toSummary(record))
                .all());
    }

    // Members of a department, optionally including all of its sub-departments.
    public Page<EmployeeResponse> departmentMembers(String departmentId, boolean includeSubDepartments,
                                                    Pageable pageable) {
        String match = includeSubDepartments
                ? "MATCH (e:Employee)-[:MEMBER_OF]->(:Department)-[:PART_OF*0..]->(:Department {id: $id})"
                : "MATCH (e:Employee)-[:MEMBER_OF]->(:Department {id: $id})";
        return page(match, Map.of("id", departmentId), pageable, EMPLOYEE_PROJECTION,
                EmployeeResponse.class, EmployeeQueries::toResponse);
    }

    // Skills whose name contains the text (case-insensitive; all skills when null), ordered by name.
    public Page<SkillCount> skills(String nameContains, Pageable pageable) {

        Map<String, Object> params = new HashMap<>();
        params.put("name", hasText(nameContains) ? nameContains.trim().toLowerCase(Locale.ROOT) : null);

        String match = "MATCH (s:Skill) WHERE $name IS NULL OR toLower(s.name) CONTAINS $name";

        long total = client.query(match + " RETURN count(s) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<SkillCount> content = List.copyOf(client.query(match + """
                         WITH s ORDER BY toLower(s.name), s.id SKIP $skip LIMIT $limit
                        RETURN s.name AS name,
                               COUNT { (e:Employee)-[:HAS_SKILL]->(s) WHERE e.status <> 'TERMINATED' } AS employees
                        ORDER BY toLower(name)
                        """)
                .bindAll(pageParams)
                .fetchAs(SkillCount.class)
                .mappedBy((types, record) -> new SkillCount(text(record.get("name")),
                        record.get("employees").asLong()))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    // The employee's skills with their levels, by name.
    public List<SkillResponse> skillsOf(String employeeId) {
        return List.copyOf(client.query("""
                        MATCH (:Employee {id: $id})-[r:HAS_SKILL]->(s:Skill)
                        RETURN s.id AS id, s.name AS name, r.level AS level
                        ORDER BY toLower(name)
                        """)
                .bind(employeeId).to("id")
                .fetchAs(SkillResponse.class)
                .mappedBy((types, record) -> new SkillResponse(text(record.get("id")), text(record.get("name")),
                        record.get("level").asInt(Skill.DEFAULT_LEVEL)))
                .all());
    }

    public long countDirectReports(String employeeId) {
        return client.query("RETURN COUNT { (:Employee)-[:REPORTS_TO]->(:Employee {id: $id}) } AS count")
                .bind(employeeId).to("id")
                .fetchAs(Long.class).one().orElse(0L);
    }

    public boolean headsAnyDepartment(String employeeId) {
        return client.query("RETURN EXISTS { (:Department)-[:HEADED_BY]->(:Employee {id: $id}) } AS heads")
                .bind(employeeId).to("id")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    // True if the proposed manager already reports (at any depth) to the employee, or is the employee.
    public boolean wouldCreateReportingCycle(String employeeId, String managerId) {
        return client.query("""
                        MATCH (m:Employee {id: $managerId})
                        RETURN EXISTS { (m)-[:REPORTS_TO*0..]->(:Employee {id: $id}) } AS cycle
                        """)
                .bindAll(Map.of("id", employeeId, "managerId", managerId))
                .fetchAs(Boolean.class).one().orElse(false);
    }

    /**
     * Team check used for authorization: is the target below the user's own employee in the
     * reporting line (at any depth)? With includeSelf, the user's own employee also counts.
     * False if the login is disabled, not linked to an employee, or that employee is terminated.
     */
    public boolean isInTeamOf(String username, String targetEmployeeId, boolean includeSelf) {
        return client.query("""
                        MATCH (u:AppUser {username: $username})
                        WHERE coalesce(u.enabled, true)
                        MATCH (me:Employee {id: u.employeeId})
                        WHERE me.status <> 'TERMINATED'
                        MATCH (target:Employee {id: $targetId})
                        RETURN ($includeSelf AND target = me)
                            OR EXISTS { (target)-[:REPORTS_TO*1..]->(me) } AS inTeam
                        """)
                .bindAll(Map.of("username", username, "targetId", targetEmployeeId, "includeSelf", includeSelf))
                .fetchAs(Boolean.class).one().orElse(false);
    }

    // Offboarding: the terminated employee's login (if any) can no longer sign in.
    public void disableLinkedLogin(String employeeId) {
        client.query("MATCH (u:AppUser {employeeId: $id}) SET u.enabled = false")
                .bind(employeeId).to("id")
                .run();
    }

    // ---- Relationship writes ----------------------------------------------

    public void setManager(String employeeId, String managerId) {
        client.query("""
                        MATCH (e:Employee {id: $id}), (m:Employee {id: $managerId})
                        OPTIONAL MATCH (e)-[old:REPORTS_TO]->()
                        DELETE old
                        WITH DISTINCT e, m
                        CREATE (e)-[:REPORTS_TO]->(m)
                        """)
                .bindAll(Map.of("id", employeeId, "managerId", managerId))
                .run();
    }

    public void clearManager(String employeeId) {
        client.query("MATCH (:Employee {id: $id})-[r:REPORTS_TO]->() DELETE r")
                .bind(employeeId).to("id")
                .run();
    }

    public void setDepartment(String employeeId, String departmentId) {
        client.query("""
                        MATCH (e:Employee {id: $id}), (d:Department {id: $departmentId})
                        OPTIONAL MATCH (e)-[old:MEMBER_OF]->()
                        DELETE old
                        WITH DISTINCT e, d
                        CREATE (e)-[:MEMBER_OF]->(d)
                        """)
                .bindAll(Map.of("id", employeeId, "departmentId", departmentId))
                .run();
    }

    public void clearDepartment(String employeeId) {
        client.query("MATCH (:Employee {id: $id})-[r:MEMBER_OF]->() DELETE r")
                .bind(employeeId).to("id")
                .run();
    }

    public void setOffice(String employeeId, String officeId) {
        client.query("""
                        MATCH (e:Employee {id: $id}), (o:Office {id: $officeId})
                        OPTIONAL MATCH (e)-[old:LOCATED_AT]->()
                        DELETE old
                        WITH DISTINCT e, o
                        CREATE (e)-[:LOCATED_AT]->(o)
                        """)
                .bindAll(Map.of("id", employeeId, "officeId", officeId))
                .run();
    }

    public void clearOffice(String employeeId) {
        client.query("MATCH (:Employee {id: $id})-[r:LOCATED_AT]->() DELETE r")
                .bind(employeeId).to("id")
                .run();
    }

    // Adds the skill, or changes its level if the employee already has it.
    public void setSkill(String employeeId, String skillId, int level) {
        client.query("""
                        MATCH (e:Employee {id: $id}), (s:Skill {id: $skillId})
                        MERGE (e)-[r:HAS_SKILL]->(s)
                        SET r.level = $level
                        """)
                .bindAll(Map.of("id", employeeId, "skillId", skillId, "level", level))
                .run();
    }

    public void removeSkills(String employeeId, List<String> skillIds) {
        client.query("MATCH (:Employee {id: $id})-[r:HAS_SKILL]->(s:Skill) WHERE s.id IN $skillIds DELETE r")
                .bindAll(Map.of("id", employeeId, "skillIds", skillIds))
                .run();
    }

    // ---- Helpers ----------------------------------------------------------

    private <T> Page<T> page(String match, Map<String, Object> matchParams, Pageable pageable, String projection,
                             Class<T> type, Function<Record, T> mapper) {

        // For OPEN_ALLOCATION, in the match (directory search) or the projection.
        Map<String, Object> params = new HashMap<>(matchParams);
        params.put("today", LocalDate.now());

        long total = client.query(match + " RETURN count(DISTINCT e) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<T> content = List.copyOf(client.query(match
                        + " WITH DISTINCT e ORDER BY e.name, e.id SKIP $skip LIMIT $limit "
                        + projection)
                .bindAll(pageParams)
                .fetchAs(type)
                .mappedBy((types, record) -> mapper.apply(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    static EmployeeResponse toResponse(Record record) {
        Value e = record.get("employee");
        Value department = record.get("department");
        Value manager = record.get("manager");
        Value company = record.get("company");
        Value office = record.get("office");

        List<SkillResponse> skills = skillList(record.get("skills"));

        String status = text(e.get("status"));

        return new EmployeeResponse(
                text(e.get("id")),
                text(e.get("employeeCode")),
                text(e.get("name")),
                text(e.get("email")),
                text(e.get("jobTitle")),
                date(e.get("hireDate")),
                status == null ? null : EmploymentStatus.valueOf(status),
                date(e.get("terminationDate")),
                department.isNull() ? null
                        : new DepartmentSummary(text(department.get("id")), text(department.get("code")),
                                text(department.get("name"))),
                manager.isNull() ? null
                        : new EmployeeSummary(text(manager.get("id")), text(manager.get("name")),
                                text(manager.get("jobTitle"))),
                company.isNull() ? null : new CompanyResponse(text(company.get("id")), text(company.get("name"))),
                office.isNull() ? null
                        : new OfficeSummary(text(office.get("id")), text(office.get("code")),
                                text(office.get("name")), text(office.get("city"))),
                skills,
                record.get("directReportCount").asLong(),
                record.get("allocationPercent").asInt());
    }

    // A list of skill maps {id, name, level}, sorted by name. Skills without a level count as the default.
    static List<SkillResponse> skillList(Value skills) {
        return skills.asList(s -> new SkillResponse(text(s.get("id")), text(s.get("name")),
                        s.get("level").asInt(Skill.DEFAULT_LEVEL)))
                .stream()
                .sorted(Comparator.comparing(SkillResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static EmployeeSummary toSummary(Record record) {
        Value e = record.get("employee");
        return new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle")));
    }

    static String text(Value value) {
        return value == null || value.isNull() ? null : value.asString();
    }

    static LocalDate date(Value value) {
        return value == null || value.isNull() ? null : value.asLocalDate();
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
