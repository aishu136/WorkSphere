package com.example.neo4j.repository;

import java.util.Optional;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Skill;

public interface SkillRepository extends Neo4jRepository<Skill, String>{

	Optional<Skill> findFirstByNameIgnoreCase(String name);

	// Reuse an existing skill node with the same name instead of creating duplicates.
	default Skill findOrCreate(String skillName) {

		String name = skillName.trim();

		return findFirstByNameIgnoreCase(name)
				.orElseGet(() -> {
					Skill newSkill = new Skill();
					newSkill.setName(name);
					return save(newSkill);
				});
	}
}
