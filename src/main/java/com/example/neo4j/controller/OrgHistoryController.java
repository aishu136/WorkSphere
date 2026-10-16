package com.example.neo4j.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ReportingHistoryResponse;
import com.example.neo4j.service.OrgHistoryService;

// The employee, a manager above them, or HR (SecurityConfig).
@RestController
public class OrgHistoryController {

    private final OrgHistoryService service;

    public OrgHistoryController(OrgHistoryService service) {
        this.service = service;
    }

    // Who they reported to and which department they were in, over time.
    @GetMapping("/employees/{id}/reporting-history")
    public ResponseEntity<ReportingHistoryResponse> history(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.history(id));
    }

    // Who reported directly to this person at the end of a day, e.g. ?date=2026-03-15.
    @GetMapping("/employees/{id}/reports-on")
    public ResponseEntity<List<EmployeeSummary>> reportsOn(
            @PathVariable("id") String id,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(service.reportsOn(id, date));
    }
}
