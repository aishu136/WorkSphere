package com.example.neo4j.dto;

import java.util.List;

// The logged-in user. employeeId is null when the login is not linked to an employee.
public record MeResponse(String username, List<String> roles, String employeeId) {
}
