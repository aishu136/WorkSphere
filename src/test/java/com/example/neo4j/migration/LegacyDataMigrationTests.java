package com.example.neo4j.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Loads data in the old Person shape (including duplicate skills and friendships),
 * lets the real migration scripts run on startup, then checks the result.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegacyDataMigrationTests {

    private static final Neo4j neo4j = EmbeddedNeo4j.start(
            """
            CREATE (a:Person {id: 'p1', name: 'Asha Rao', age: 30}),
                   (b:Person {id: 'p2', name: 'Bala', age: 41}),
                   (java1:Skill {id: 's1', name: 'Java'}),
                   (java2:Skill {id: 's2', name: 'java'}),
                   (java3:Skill {id: 's3', name: ' Java '}),
                   (py:Skill {id: 's4', name: 'Python'}),
                   (c:Company {id: 'c1', name: 'Acme'}),
                   (a)-[:HAS_SKILL]->(java1), (a)-[:HAS_SKILL]->(java2),
                   (b)-[:HAS_SKILL]->(java3), (b)-[:HAS_SKILL]->(py),
                   (a)-[:WORKS_FOR]->(c),
                   (a)-[:FRIEND_OF]->(b)
            """);

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        EmbeddedNeo4j.register(registry, neo4j);
    }

    @AfterAll
    static void stopNeo4j() {
        neo4j.close();
    }

    @Autowired
    Neo4jClient client;

    private long count(String cypher) {
        return client.query(cypher).fetchAs(Long.class).one().orElseThrow();
    }

    @Test
    void personNodesBecomeActiveEmployeesWithSearchName() {
        assertThat(count("MATCH (p:Person) RETURN count(p)")).isZero();
        assertThat(count("MATCH (e:Employee) RETURN count(e)")).isEqualTo(2);
        assertThat(count("MATCH (e:Employee) WHERE e.status = 'ACTIVE' RETURN count(e)")).isEqualTo(2);

        String searchName = client.query("MATCH (e:Employee {id: 'p1'}) RETURN e.searchName")
                .fetchAs(String.class).one().orElseThrow();
        assertThat(searchName).isEqualTo("asha rao");
    }

    @Test
    void duplicateSkillsAreMergedAndLinksKept() {
        assertThat(count("MATCH (s:Skill) WHERE toLower(trim(s.name)) = 'java' RETURN count(s)")).isEqualTo(1);
        assertThat(count("MATCH (s:Skill) RETURN count(s)")).isEqualTo(2);

        // Asha had two Java nodes -> exactly one link now; Bala keeps Java and Python.
        assertThat(count("MATCH (:Employee {id: 'p1'})-[r:HAS_SKILL]->() RETURN count(r)")).isEqualTo(1);
        List<String> balaSkills = List.copyOf(client
                .query("MATCH (:Employee {id: 'p2'})-[:HAS_SKILL]->(s) RETURN toLower(trim(s.name)) AS name ORDER BY name")
                .fetchAs(String.class).all());
        assertThat(balaSkills).containsExactly("java", "python");
    }

    @Test
    void nothingIsLostIrreversibly() {
        assertThat(count("MATCH (:Employee)-[r:FRIEND_OF]->(:Employee) RETURN count(r)")).isEqualTo(1);
        assertThat(count("MATCH (e:Employee) WHERE e.age IS NOT NULL RETURN count(e)")).isEqualTo(2);
        assertThat(count("MATCH (:Employee {id: 'p1'})-[:WORKS_FOR]->(:Company) RETURN count(*)")).isEqualTo(1);
    }

    @Test
    void constraintsAndIndexesAreCreated() {
        List<String> names = List.copyOf(client.query("SHOW CONSTRAINTS YIELD name RETURN name")
                .fetchAs(String.class).all());
        assertThat(names).contains("employee_code", "employee_email", "department_code", "skill_name",
                "app_user_username");

        List<Map<String, Object>> indexes = List.copyOf(client.query("SHOW INDEXES YIELD name RETURN name").fetch().all());
        assertThat(indexes).extracting(row -> row.get("name")).contains("employee_search_name");
    }
}
