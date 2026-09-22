package com.example.neo4j.dto;

import java.util.List;

import com.example.neo4j.entity.AppUser;

public record UserResponse(String id, String username, List<String> roles, boolean enabled, String employeeId) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRoles(), user.isEnabled(),
                user.getEmployeeId());
    }
}
