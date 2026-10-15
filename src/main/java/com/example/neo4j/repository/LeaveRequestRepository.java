package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.LeaveRequest;

// Entity writes only. Reads for the API live in LeaveQueries.
public interface LeaveRequestRepository extends Neo4jRepository<LeaveRequest, String> {
}
