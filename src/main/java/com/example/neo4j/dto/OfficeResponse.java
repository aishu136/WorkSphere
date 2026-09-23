package com.example.neo4j.dto;

public record OfficeResponse(
        String id,
        String code,
        String name,
        String city,
        String country,
        String address,
        Integer capacity,
        long employeeCount) {
}
