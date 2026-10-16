package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(

        @NotBlank
        @Size(max = 200)
        String refreshToken) {

    // Keep the token out of logs if this record is ever printed.
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=***]";
    }
}
