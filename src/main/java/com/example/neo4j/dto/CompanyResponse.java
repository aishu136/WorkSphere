package com.example.neo4j.dto;

import com.example.neo4j.entity.Company;

public record CompanyResponse(String id, String name) {

    public static CompanyResponse from(Company company) {
        return company == null ? null : new CompanyResponse(company.getId(), company.getName());
    }
}
