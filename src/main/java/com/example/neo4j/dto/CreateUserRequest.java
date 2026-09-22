package com.example.neo4j.dto;

import java.util.Set;

import com.example.neo4j.security.Role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "[A-Za-z0-9._@-]+", message = "may contain only letters, digits and . _ @ -")
        String username,

        @NotBlank
        @Size(min = 12, max = 128)
        String password,

        @NotEmpty
        Set<Role> roles,

        // Optional: link the login to an employee record.
        String employeeId) {

    // Keep the password out of logs if this record is ever printed.
    @Override
    public String toString() {
        return "CreateUserRequest[username=" + username + ", roles=" + roles + ", employeeId=" + employeeId + "]";
    }
}
