package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.StaffingRequest;
import com.example.neo4j.entity.StaffingRequestStatus;

// Entity writes only. Reads for the API live in StaffingQueries.
public interface StaffingRequestRepository extends Neo4jRepository<StaffingRequest, String> {

	boolean existsByProjectIdAndEmployeeIdAndStatus(String projectId, String employeeId, StaffingRequestStatus status);
}
