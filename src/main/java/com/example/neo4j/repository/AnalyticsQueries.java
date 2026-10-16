package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.text;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import com.example.neo4j.dto.DepartmentSkillGapResponse;
import com.example.neo4j.dto.DepartmentSkillGapResponse.SkillSupply;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ManagerSpanResponse;
import com.example.neo4j.dto.OrgOverviewResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse.SkillCoverage;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.entity.ProjectStatus;

/**
 * Read-only queries about the shape of the organisation. Terminated employees have no reporting,
 * department or project links (see EmployeeService.terminate), so they drop out naturally.
 */
@Repository
public class AnalyticsQueries {

    private static final String OPEN = ProjectStatus.OPEN_CYPHER_LIST;

    private final Neo4jClient client;

    public AnalyticsQueries(Neo4jClient client) {
        this.client = client;
    }

    public OrgOverviewResponse overview() {

        Map<String, Object> people = client.query("""
                        MATCH (e:Employee) WHERE e.status <> 'TERMINATED'
                        RETURN count(e) AS headcount,
                               count(CASE WHEN e.status = 'ON_LEAVE' THEN 1 END) AS onLeave,
                               count(CASE WHEN NOT EXISTS { (e)-[:REPORTS_TO]->() } THEN 1 END) AS withoutManager,
                               count(CASE WHEN NOT EXISTS { (e)-[:MEMBER_OF]->() } THEN 1 END) AS withoutDepartment
                        """)
                .fetch().one().orElseThrow();

        Map<String, Object> spans = client.query("""
                        MATCH (m:Employee)<-[:REPORTS_TO]-(:Employee)
                        WITH m, count(*) AS span
                        RETURN count(m) AS managers, coalesce(avg(span), 0.0) AS averageSpan,
                               coalesce(max(span), 0) AS widestSpan
                        """)
                .fetch().one().orElseThrow();

        // Levels in the longest line from someone up to the top; 0 when there is nobody.
        long depth = client.query("""
                        MATCH path = (:Employee)-[:REPORTS_TO*0..]->(top:Employee)
                        WHERE top.status <> 'TERMINATED' AND NOT EXISTS { (top)-[:REPORTS_TO]->() }
                        RETURN coalesce(max(length(path)) + 1, 0) AS depth
                        """)
                .fetchAs(Long.class).one().orElse(0L);

        return new OrgOverviewResponse(
                number(people.get("headcount")),
                number(people.get("onLeave")),
                number(spans.get("managers")),
                Math.round(((Number) spans.get("averageSpan")).doubleValue() * 10) / 10.0,
                (int) number(spans.get("widestSpan")),
                (int) depth,
                number(people.get("withoutManager")),
                number(people.get("withoutDepartment")));
    }

    /** Managers with the most direct reports first. */
    public Page<ManagerSpanResponse> managers(int maxSpan, Pageable pageable) {

        long total = client.query("MATCH (m:Employee) WHERE EXISTS { (:Employee)-[:REPORTS_TO]->(m) } RETURN count(m)")
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> params = new HashMap<>();
        params.put("skip", pageable.getOffset());
        params.put("limit", pageable.getPageSize());

        List<ManagerSpanResponse> content = List.copyOf(client.query("""
                        MATCH (m:Employee) WHERE EXISTS { (:Employee)-[:REPORTS_TO]->(m) }
                        WITH m, COUNT { (:Employee)-[:REPORTS_TO]->(m) } AS direct
                        ORDER BY direct DESC, m.name, m.id
                        SKIP $skip LIMIT $limit
                        CALL {
                            WITH m
                            MATCH path = (:Employee)-[:REPORTS_TO*1..]->(m)
                            RETURN count(path) AS total, max(length(path)) AS layers
                        }
                        RETURN m {.id, .name, .jobTitle} AS manager, direct, total, layers
                        ORDER BY direct DESC, manager.name, manager.id
                        """)
                .bindAll(params)
                .fetchAs(ManagerSpanResponse.class)
                .mappedBy((types, record) -> {
                    int direct = record.get("direct").asInt();
                    String flag = direct > maxSpan ? "WIDE" : direct == 1 ? "NARROW" : null;
                    return new ManagerSpanResponse(summary(record.get("manager")), direct,
                            record.get("total").asInt(), record.get("layers").asInt(), flag);
                })
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Required skills of open projects and the current members who have them at the required level.
     * Current members are those whose assignment hasn't ended.
     */
    public List<ProjectSkillRiskResponse> projectSkillCoverage(LocalDate today) {
        return List.copyOf(client.query("""
                        MATCH (p:Project)-[req:REQUIRES]->(s:Skill) WHERE p.status IN %s
                        WITH p, req, s ORDER BY toLower(s.name)
                        WITH p, collect({id: s.id, name: s.name, level: req.level}) AS required
                        RETURN p {.id, .code, .name, .status} AS project,
                               [r IN required | {name: r.name, level: r.level,
                                   coveredBy: [(m:Employee)-[w:WORKS_ON]->(p)
                                       WHERE (w.endDate IS NULL OR w.endDate >= $today)
                                         AND EXISTS { (m)-[h:HAS_SKILL]->(:Skill {id: r.id}) WHERE h.level >= r.level }
                                       | m {.id, .name, .jobTitle}]}] AS skills
                        ORDER BY project.name, project.id
                        """.formatted(OPEN))
                .bind(today).to("today")
                .fetchAs(ProjectSkillRiskResponse.class)
                .mappedBy((types, record) -> toRisk(record))
                .all());
    }

    /** For each department owning open projects with required skills: demand against qualified active people. */
    public List<DepartmentSkillGapResponse> departmentSkillSupply(String departmentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("departmentId", departmentId);

        return List.copyOf(client.query("""
                        MATCH (d:Department)<-[:OWNED_BY]-(p:Project)-[req:REQUIRES]->(s:Skill)
                        WHERE p.status IN %s AND ($departmentId IS NULL OR d.id = $departmentId)
                        WITH d, s, max(req.level) AS level, count(DISTINCT p) AS projects
                        WITH d, s, level, projects,
                             COUNT {
                                 MATCH (e:Employee)-[:MEMBER_OF]->(:Department)-[:PART_OF*0..]->(d)
                                 WHERE e.status = 'ACTIVE'
                                   AND EXISTS { (e)-[h:HAS_SKILL]->(s) WHERE h.level >= level }
                             } AS qualified
                        ORDER BY d.name, d.id, toLower(s.name)
                        RETURN d {.id, .code, .name} AS department,
                               collect({skill: s.name, level: level, projects: projects, qualified: qualified}) AS skills
                        ORDER BY department.name, department.id
                        """.formatted(OPEN))
                .bindAll(params)
                .fetchAs(DepartmentSkillGapResponse.class)
                .mappedBy((types, record) -> toGap(record))
                .all());
    }

    // ---- Mapping ----------------------------------------------------------

    private static ProjectSkillRiskResponse toRisk(Record record) {
        Value p = record.get("project");
        List<SkillCoverage> skills = record.get("skills").asList(s -> {
            List<EmployeeSummary> coveredBy = s.get("coveredBy").asList(AnalyticsQueries::summary);
            return new SkillCoverage(text(s.get("name")), s.get("level").asInt(), coveredBy, coveredBy.size() <= 1);
        });
        return new ProjectSkillRiskResponse(
                new ProjectSummary(text(p.get("id")), text(p.get("code")), text(p.get("name")),
                        ProjectStatus.valueOf(text(p.get("status")))),
                (int) skills.stream().filter(SkillCoverage::atRisk).count(),
                skills);
    }

    private static DepartmentSkillGapResponse toGap(Record record) {
        Value d = record.get("department");
        List<SkillSupply> skills = record.get("skills").asList(s -> {
            int projects = s.get("projects").asInt();
            int qualified = s.get("qualified").asInt();
            return new SkillSupply(text(s.get("skill")), s.get("level").asInt(), projects, qualified,
                    qualified < projects);
        });
        return new DepartmentSkillGapResponse(
                new DepartmentSummary(text(d.get("id")), text(d.get("code")), text(d.get("name"))),
                (int) skills.stream().filter(SkillSupply::shortage).count(),
                skills);
    }

    private static EmployeeSummary summary(Value e) {
        return new EmployeeSummary(text(e.get("id")), text(e.get("name")), text(e.get("jobTitle")));
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
