package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(

        @NotBlank
        @Size(max = 100)
        String username,

        @NotBlank
        String password) {

    // Keep the password out of logs if this record is ever printed.
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
