package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Employee;

// Entity writes only. Reads for the API and org-chart relationships live in EmployeeQueries.
public interface EmployeeRepository extends Neo4jRepository<Employee, String> {

	boolean existsByEmployeeCode(String employeeCode);

	boolean existsByEmail(String email);
}
