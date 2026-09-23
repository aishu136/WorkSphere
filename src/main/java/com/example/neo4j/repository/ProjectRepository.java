package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Project;

// Entity writes only. Reads for the API and member/lead/department relationships live in ProjectQueries.
public interface ProjectRepository extends Neo4jRepository<Project, String> {

	boolean existsByCode(String code);
}
