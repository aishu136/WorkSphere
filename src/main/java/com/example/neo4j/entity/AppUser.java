package com.example.neo4j.entity;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

@Node("AppUser")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    private String username;

    // BCrypt hash - never the plain password.
    private String passwordHash;

    // Role names, e.g. ["HR"]; see com.example.neo4j.security.Role.
    private List<String> roles = new ArrayList<>();

    private boolean enabled = true;

    // The employee this login belongs to (optional). Managers get rights over the
    // people who report to this employee. Unique - see migration V0003.
    private String employeeId;
}
