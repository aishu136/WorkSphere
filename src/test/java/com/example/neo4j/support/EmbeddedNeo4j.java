package com.example.neo4j.support;

import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Starts an in-process Neo4j for tests, optionally pre-loaded with Cypher fixture statements. */
public final class EmbeddedNeo4j {

    private static Neo4j shared;

    private EmbeddedNeo4j() {
    }

    /**
     * One empty database for every test class whose tests roll back (see SharedNeo4jTest), started on
     * first use and stopped when the test JVM exits. Starting a database and running the migrations is
     * the slowest part of a test class, so sharing it saves most of the suite's time and memory.
     */
    public static synchronized Neo4j shared() {
        if (shared == null) {
            shared = start();
            Neo4j server = shared;
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "embedded-neo4j-shutdown"));
        }
        return shared;
    }

    public static Neo4j start(String... fixtureStatements) {
        Neo4j server = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
        for (String statement : fixtureStatements) {
            server.defaultDatabaseService().executeTransactionally(statement);
        }
        return server;
    }

    public static void register(DynamicPropertyRegistry registry, Neo4j neo4j) {
        registry.add("spring.neo4j.uri", neo4j::boltURI);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        // Auth is disabled on the embedded server; any password is accepted.
        registry.add("spring.neo4j.authentication.password", () -> "unused");
        registry.add("spring.data.neo4j.database", () -> "neo4j");
    }
}
