package com.example.neo4j.controller;

import java.time.LocalDate;
import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateHolidayRequest;
import com.example.neo4j.dto.CreateOfficeRequest;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.HolidayResponse;
import com.example.neo4j.dto.OfficeResponse;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.UpdateOfficeRequest;
import com.example.neo4j.service.OfficeService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// Reads: any logged-in user. Changes: HR or ADMIN (enforced in SecurityConfig).
@RestController
@RequestMapping("/offices")
public class OfficeController {

    private final OfficeService service;

    public OfficeController(OfficeService service) {
        this.service = service;
    }

    // Optional ?q= matches office name or city.
    @GetMapping
    public ResponseEntity<PageResponse<OfficeResponse>> search(
            @RequestParam(name = "q", required = false) String q,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(q, paging.toPageable())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OfficeResponse> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @GetMapping("/{id}/employees")
    public ResponseEntity<PageResponse<EmployeeResponse>> employees(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.employees(id, paging.toPageable())));
    }

    @PostMapping
    public ResponseEntity<OfficeResponse> create(@RequestBody @Valid CreateOfficeRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OfficeResponse> update(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateOfficeRequest request) {

        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Public holidays ------------------------------------------------------

    // ?year=, this year by default.
    @GetMapping("/{id}/holidays")
    public ResponseEntity<List<HolidayResponse>> holidays(
            @PathVariable("id") String id,
            @RequestParam(name = "year", required = false) @Min(2000) @Max(2100) Integer year) {

        return ResponseEntity.ok(service.holidays(id, year == null ? LocalDate.now().getYear() : year));
    }

    @PostMapping("/{id}/holidays")
    public ResponseEntity<HolidayResponse> addHoliday(
            @PathVariable("id") String id,
            @RequestBody @Valid CreateHolidayRequest request) {

        return new ResponseEntity<>(service.addHoliday(id, request), HttpStatus.CREATED);
    }

    @DeleteMapping("/{id}/holidays/{holidayId}")
    public ResponseEntity<Void> removeHoliday(
            @PathVariable("id") String id,
            @PathVariable("holidayId") String holidayId) {

        service.removeHoliday(id, holidayId);
        return ResponseEntity.noContent().build();
    }
}
