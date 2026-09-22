package com.example.neo4j.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.DepartmentResponse;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.UpdateDepartmentRequest;
import com.example.neo4j.service.DepartmentService;

import jakarta.validation.Valid;

// Reads: any logged-in user. Changes: HR or ADMIN (enforced in SecurityConfig).
@RestController
@RequestMapping("/departments")
public class DepartmentController {

    private final DepartmentService service;

    public DepartmentController(DepartmentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PageResponse<DepartmentResponse>> getAll(
            @RequestParam(name = "topLevelOnly", defaultValue = "false") boolean topLevelOnly,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.findAll(topLevelOnly, paging.toPageable())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DepartmentResponse> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<DepartmentResponse> create(@RequestBody @Valid CreateDepartmentRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DepartmentResponse> update(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateDepartmentRequest request) {

        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<PageResponse<EmployeeResponse>> members(
            @PathVariable("id") String id,
            @RequestParam(name = "includeSubDepartments", defaultValue = "false") boolean includeSubDepartments,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.members(id, includeSubDepartments, paging.toPageable())));
    }

    @GetMapping("/{id}/sub-departments")
    public ResponseEntity<PageResponse<DepartmentResponse>> subDepartments(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.subDepartments(id, paging.toPageable())));
    }

    @PutMapping("/{id}/parent/{parentId}")
    public ResponseEntity<DepartmentResponse> setParent(
            @PathVariable("id") String id,
            @PathVariable("parentId") String parentId) {

        return ResponseEntity.ok(service.setParent(id, parentId));
    }

    @DeleteMapping("/{id}/parent")
    public ResponseEntity<DepartmentResponse> removeParent(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeParent(id));
    }

    @PutMapping("/{id}/head/{employeeId}")
    public ResponseEntity<DepartmentResponse> setHead(
            @PathVariable("id") String id,
            @PathVariable("employeeId") String employeeId) {

        return ResponseEntity.ok(service.setHead(id, employeeId));
    }

    @DeleteMapping("/{id}/head")
    public ResponseEntity<DepartmentResponse> removeHead(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeHead(id));
    }
}
