package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Department;

// Entity writes only. Reads for the API and hierarchy relationships live in DepartmentQueries.
public interface DepartmentRepository extends Neo4jRepository<Department, String> {

	boolean existsByCode(String code);
}
