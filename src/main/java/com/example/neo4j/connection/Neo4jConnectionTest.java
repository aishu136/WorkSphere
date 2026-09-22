package com.example.neo4j.connection;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

public class Neo4jConnectionTest {

    public static void main(String[] args) {

        final String dbUri = System.getenv("NEO4J_URI");

        final String dbUser = System.getenv("NEO4J_USERNAME");

        final String dbPassword = System.getenv("NEO4J_PASSWORD");

        try (var driver = GraphDatabase.driver(
                dbUri,
                AuthTokens.basic(dbUser, dbPassword))) {

            driver.verifyConnectivity();

            System.out.println("Connection established.");
        }
    }
}