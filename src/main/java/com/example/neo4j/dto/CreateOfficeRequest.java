package com.example.neo4j.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOfficeRequest(

        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9-]{2,20}", message = "must be 2-20 letters, digits or -")
        String code,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 100)
        String city,

        @NotBlank
        @Size(max = 100)
        String country,

        @Size(max = 300)
        String address,

        @Min(1)
        Integer capacity) {
}
