package com.example.neo4j.dto;

import java.time.LocalDate;

public record HolidayResponse(String id, LocalDate date, String name) {
}
