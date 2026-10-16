package com.example.neo4j.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Points the test at the shared embedded Neo4j. See {@link ServiceTest}. */
public abstract class SharedNeo4jTest {

    @DynamicPropertySource
    static void sharedNeo4j(DynamicPropertyRegistry registry) {
        EmbeddedNeo4j.register(registry, EmbeddedNeo4j.shared());
    }
}
