package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Office;

// Entity writes only. Reads for the API live in OfficeQueries.
public interface OfficeRepository extends Neo4jRepository<Office, String> {

	boolean existsByCode(String code);
}
