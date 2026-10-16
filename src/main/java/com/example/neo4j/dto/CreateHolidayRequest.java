package com.example.neo4j.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A public holiday an office observes, e.g. {"date":"2026-12-25","name":"Christmas Day"}. */
public record CreateHolidayRequest(

        @NotNull
        LocalDate date,

        @NotBlank
        @Size(max = 100)
        String name) {
}
