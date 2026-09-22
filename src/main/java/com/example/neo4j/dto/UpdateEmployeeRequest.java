package com.example.neo4j.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Employee code is the permanent business key and cannot be changed.
public record UpdateEmployeeRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @Size(max = 100)
        String jobTitle,

        @NotNull
        LocalDate hireDate) {
}
