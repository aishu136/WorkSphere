package com.example.neo4j.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeProjectsResponse;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.ProjectStatusRequest;
import com.example.neo4j.dto.UpdateProjectRequest;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.service.ProjectService;

import jakarta.validation.Valid;

// Reads: any logged-in user. Changes: HR or ADMIN (enforced in SecurityConfig).
@RestController
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    public ResponseEntity<PageResponse<ProjectResponse>> search(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "status", required = false) ProjectStatus status,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(name, status, departmentId, paging.toPageable())));
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectResponse> create(@RequestBody @Valid CreateProjectRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/projects/{id}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateProjectRequest request) {

        return ResponseEntity.ok(service.update(id, request));
    }

    @PutMapping("/projects/{id}/status")
    public ResponseEntity<ProjectResponse> changeStatus(
            @PathVariable("id") String id,
            @RequestBody @Valid ProjectStatusRequest request) {

        return ResponseEntity.ok(service.changeStatus(id, request.status()));
    }

    // Only projects that never had members can be deleted; otherwise set status CANCELLED.
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/projects/{id}/lead/{employeeId}")
    public ResponseEntity<ProjectResponse> setLead(
            @PathVariable("id") String id,
            @PathVariable("employeeId") String employeeId) {

        return ResponseEntity.ok(service.setLead(id, employeeId));
    }

    @DeleteMapping("/projects/{id}/lead")
    public ResponseEntity<ProjectResponse> removeLead(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeLead(id));
    }

    @PutMapping("/projects/{id}/department/{departmentId}")
    public ResponseEntity<ProjectResponse> setDepartment(
            @PathVariable("id") String id,
            @PathVariable("departmentId") String departmentId) {

        return ResponseEntity.ok(service.setDepartment(id, departmentId));
    }

    @DeleteMapping("/projects/{id}/department")
    public ResponseEntity<ProjectResponse> removeDepartment(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeDepartment(id));
    }

    // ---- Members --------------------------------------------------------------

    @GetMapping("/projects/{id}/members")
    public ResponseEntity<PageResponse<ProjectMemberResponse>> members(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.members(id, paging.toPageable())));
    }

    // Adds the employee, or updates their role/allocation. Total open allocation must stay <= 100%.
    @PutMapping("/projects/{id}/members/{employeeId}")
    public ResponseEntity<ProjectMemberResponse> assign(
            @PathVariable("id") String id,
            @PathVariable("employeeId") String employeeId,
            @RequestBody @Valid AssignmentRequest request) {

        return ResponseEntity.ok(service.assign(id, employeeId, request));
    }

    @DeleteMapping("/projects/{id}/members/{employeeId}")
    public ResponseEntity<Void> unassign(
            @PathVariable("id") String id,
            @PathVariable("employeeId") String employeeId) {

        service.unassign(id, employeeId);
        return ResponseEntity.noContent().build();
    }

    // An employee's projects and total allocation on open ones.
    @GetMapping("/employees/{employeeId}/projects")
    public ResponseEntity<EmployeeProjectsResponse> projectsOf(@PathVariable("employeeId") String employeeId) {
        return ResponseEntity.ok(service.projectsOf(employeeId));
    }
}
