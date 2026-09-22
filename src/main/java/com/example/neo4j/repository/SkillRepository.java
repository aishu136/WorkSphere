package com.example.neo4j.repository;

import java.util.Optional;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Skill;

public interface SkillRepository extends Neo4jRepository<Skill, String>{

	Optional<Skill> findFirstByNameIgnoreCase(String name);
}
