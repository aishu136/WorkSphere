package com.example.neo4j.repository;

import java.util.Optional;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.AppUser;

public interface AppUserRepository extends Neo4jRepository<AppUser, String> {

	Optional<AppUser> findByUsername(String username);

	boolean existsByUsername(String username);

	Optional<AppUser> findByEmployeeId(String employeeId);
}
