package com.example.neo4j.repository;

import org.springframework.data.neo4j.repository.Neo4jRepository;

import com.example.neo4j.entity.Company;


public interface CompanyRepository extends Neo4jRepository<Company, String>{

}
