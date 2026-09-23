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

import com.example.neo4j.dto.OfficeResponse;

/** Hand-written Cypher for office reads. Employees link to offices with LOCATED_AT. */
@Repository
public class OfficeQueries {

    private static final String PROJECTION = """
            RETURN o {.id, .code, .name, .city, .country, .address, .capacity} AS office,
                   COUNT { (:Employee)-[:LOCATED_AT]->(o) } AS employeeCount
            ORDER BY office.name, office.id
            """;

    private final Neo4jClient client;

    public OfficeQueries(Neo4jClient client) {
        this.client = client;
    }

    public Optional<OfficeResponse> findById(String id) {
        return client.query("MATCH (o:Office {id: $id}) " + PROJECTION)
                .bind(id).to("id")
                .fetchAs(OfficeResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .one();
    }

    // Optional case-insensitive filter on name or city.
    public Page<OfficeResponse> search(String text, Pageable pageable) {

        Map<String, Object> params = new HashMap<>();
        String match = "MATCH (o:Office)";
        if (EmployeeQueries.hasText(text)) {
            match += " WHERE toLower(o.name) CONTAINS $text OR toLower(o.city) CONTAINS $text";
            params.put("text", text.trim().toLowerCase());
        }

        long total = client.query(match + " RETURN count(o) AS total")
                .bindAll(params)
                .fetchAs(Long.class).one().orElse(0L);

        Map<String, Object> pageParams = new HashMap<>(params);
        pageParams.put("skip", pageable.getOffset());
        pageParams.put("limit", pageable.getPageSize());

        List<OfficeResponse> content = List.copyOf(client.query(match
                        + " WITH o ORDER BY o.name, o.id SKIP $skip LIMIT $limit " + PROJECTION)
                .bindAll(pageParams)
                .fetchAs(OfficeResponse.class)
                .mappedBy((types, record) -> toResponse(record))
                .all());

        return new PageImpl<>(content, pageable, total);
    }

    public boolean hasEmployees(String officeId) {
        return client.query("RETURN EXISTS { (:Employee)-[:LOCATED_AT]->(:Office {id: $id}) } AS inUse")
                .bind(officeId).to("id")
                .fetchAs(Boolean.class).one().orElse(false);
    }

    private static OfficeResponse toResponse(Record record) {
        Value o = record.get("office");
        return new OfficeResponse(
                text(o.get("id")),
                text(o.get("code")),
                text(o.get("name")),
                text(o.get("city")),
                text(o.get("country")),
                text(o.get("address")),
                o.get("capacity").isNull() ? null : o.get("capacity").asInt(),
                record.get("employeeCount").asLong());
    }
}
