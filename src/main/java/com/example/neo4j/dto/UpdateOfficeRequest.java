package com.example.neo4j.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Office code is the permanent business key and cannot be changed.
public record UpdateOfficeRequest(

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
