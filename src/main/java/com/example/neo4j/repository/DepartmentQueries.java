package com.example.neo4j.repository;

import static com.example.neo4j.repository.EmployeeQueries.text;

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

import com.example.neo4j.dto.DepartmentResponse;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeSummary;

/**
 * Hand-written Cypher for department reads and for the PART_OF (parent) and
 * HEADED_BY (head) relationships that are deliberately not mapped on the entity.
 */
@Repository
public class DepartmentQueries {

    private static final String PROJECTION = """
            RETURN d {.id, .code, .name, .description} AS department,
                   head([(d)-[:PART_OF]->(p:Department) | p {.id, .code, .name}]) AS parent,
                   head([(d)-[:HEADED_BY]->(h:Employee) | h {.id, .name, .jobTitle}]) AS head,
                   COUNT { (:Employee)-[:MEMBER_OF]->(d) } AS memberCount,
                   COUNT { (:Department)-[:PART_OF]->(d) } AS subDepartmentCount
            ORDER BY department.name, department.id
            """;

    private final Neo4jClient client;

    public DepartmentQueries(Neo4jClient client) {
        this.client = client;
    }

    // ---- Reads ------------------------------------------------------------

    public Optional<DepartmentResponse> findById(String id) {
        return client.query("MATCH (d:Department {id: $id}) " + PROJECTION)
                .bind(id).to("id")
                .fetchAs(DepartmentResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    public Page<DepartmentResponse> findAll(boolean topLevelOnly, Pageable pageable) {
        String match = topLevelOnly
                ? "MATCH (d:Department) WHERE NOT (d)-[:PART_OF]->(:Department)"
                : "MATCH (d:Department)";
        return page(match, Map.of(), pageable);
    }

    public Page<DepartmentResponse> subDepartments(String parentId, Pageable pageable) {
        return page("MATCH (d:Department)-[:PART_OF]->(:Department {id: $id})", Map.of("id", parentId), pageable);
    }

    // True if the proposed parent is the department itself or already sits below it.
    public boolean wouldCreateCycle(String departmentId, String parentId) {
        return client.query("""
                        MATCH (p:Department {id: $parentId})
                        RETURN EXISTS { (p)-[:PART_OF*0..]->(:Department {id: $id}) } AS cycle
                        """)
                .bindAll(Map.of("id", departmentId, "parentId", parentId))
                .fetchAs(Boolean.class).one().orElse(false);
    }

    public boolean hasMembersOrSubDepartments(String departmentId) {
        return client.query("""
                        MATCH (d:Department {id: $id})
                        RETURN EXISTS { (:Employee)-[:MEMBER_OF]->(d) }
                            OR EXISTS { (:Department)-[:PART_OF]->(d) } AS inUse
                        """)
                .bind(departmentId).to("id")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    // ---- Relationship writes ----------------------------------------------

    public void setParent(String departmentId, String parentId) {
        client.query("""
                        MATCH (d:Department {id: $id}), (p:Department {id: $parentId})
                        OPTIONAL MATCH (d)-[old:PART_OF]->()
                        DELETE old
                        WITH DISTINCT d, p
                        CREATE (d)-[:PART_OF]->(p)
                        """)
                .bindAll(Map.of("id", departmentId, "parentId", parentId))
                .run();
    }

    public void clearParent(String departmentId) {
        client.query("MATCH (:Department {id: $id})-[r:PART_OF]->() DELETE r")
                .bind(departmentId).to("id")
                .run();
    }

    public void setHead(String departmentId, String employeeId) {
        client.query("""
                        MATCH (d:Department {id: $id}), (e:Employee {id: $employeeId})
                        OPTIONAL MATCH (d)-[old:HEADED_BY]->()
                        DELETE old
                        WITH DISTINCT d, e
                        CREATE (d)-[:HEADED_BY]->(e)
                        """)
                .bindAll(Map.of("id", departmentId, "employeeId", employeeId))
                .run();
    }

    public void clearHead(String departmentId) {
        client.query("MATCH (:Department {id: $id})-[r:HEADED_BY]->() DELETE r")
                .bind(departmentId).to("id")
                .run();
    }

    // ---- Helpers ----------------------------------------------------------

    private Page<DepartmentResponse> page(String match, Map<String, Object> params, Pageable pageable) {

        long total = client.query(match + " RETURN count(d) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<DepartmentResponse> content = List.copyOf(client.query(match
                        + " WITH d ORDER BY d.name, d.id SKIP $skip LIMIT $limit " + PROJECTION)
                .bindAll(pageParams)
                .fetchAs(DepartmentResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    private static DepartmentResponse toResponse(Record record) {
        Value d = record.get("department");
        Value parent = record.get("parent");
        Value head = record.get("head");

        return new DepartmentResponse(
                text(d.get("id")),
                text(d.get("code")),
                text(d.get("name")),
                text(d.get("description")),
                parent.isNull() ? null
                        : new DepartmentSummary(text(parent.get("id")), text(parent.get("code")),
                                text(parent.get("name"))),
                head.isNull() ? null
                        : new EmployeeSummary(text(head.get("id")), text(head.get("name")),
                                text(head.get("jobTitle"))),
                record.get("memberCount").asLong(),
                record.get("subDepartmentCount").asLong());
    }
}
