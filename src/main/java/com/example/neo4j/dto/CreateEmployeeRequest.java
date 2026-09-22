package com.example.neo4j.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateEmployeeRequest(

        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9-]{2,20}", message = "must be 2-20 letters, digits or -")
        String employeeCode,

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
        LocalDate hireDate,

        // Optional: place the new hire in a department and under a manager straight away.
        String departmentId,

        String managerId) {
}
