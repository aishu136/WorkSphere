package com.example.neo4j.controller;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.StatusRequest;
import com.example.neo4j.dto.UpdateEmployeeRequest;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.UserService;

import jakarta.validation.Valid;

// Reads: any logged-in user. Changes: HR or ADMIN, plus a few manager self-service
// endpoints for their own team (status, skills, moving reports) - see SecurityConfig.
@RestController
@RequestMapping("/employees")
public class EmployeeController {

    private final EmployeeService service;
    private final UserService userService;

    public EmployeeController(EmployeeService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    // ---- Directory ------------------------------------------------------------

    @GetMapping
    public ResponseEntity<PageResponse<EmployeeResponse>> search(
            @Valid @ParameterObject EmployeeFilter filter,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(filter, paging.toPageable())));
    }

    // The logged-in user's own employee profile.
    @GetMapping("/me")
    public ResponseEntity<EmployeeResponse> me(Authentication authentication) {

        String employeeId = userService.findEmployeeId(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Your login is not linked to an employee"));

        return ResponseEntity.ok(service.findById(employeeId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeResponse> getById(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.findById(id));
    }

    // ---- Lifecycle ------------------------------------------------------------

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@RequestBody @Valid CreateEmployeeRequest request) {
        return new ResponseEntity<>(service.create(request), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmployeeResponse> update(
            @PathVariable("id") String id,
            @RequestBody @Valid UpdateEmployeeRequest request) {

        return ResponseEntity.ok(service.update(id, request));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<EmployeeResponse> changeStatus(
            @PathVariable("id") String id,
            @RequestBody @Valid StatusRequest request) {

        return ResponseEntity.ok(service.changeStatus(id, request.status()));
    }

    // Offboarding: marks the employee TERMINATED; the record is kept.
    @DeleteMapping("/{id}")
    public ResponseEntity<EmployeeResponse> terminate(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.terminate(id));
    }

    // ---- Reporting line -------------------------------------------------------

    @PutMapping("/{id}/manager/{managerId}")
    public ResponseEntity<EmployeeResponse> assignManager(
            @PathVariable("id") String id,
            @PathVariable("managerId") String managerId) {

        return ResponseEntity.ok(service.assignManager(id, managerId));
    }

    @DeleteMapping("/{id}/manager")
    public ResponseEntity<EmployeeResponse> removeManager(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeManager(id));
    }

    @GetMapping("/{id}/direct-reports")
    public ResponseEntity<PageResponse<EmployeeSummary>> directReports(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.directReports(id, paging.toPageable())));
    }

    // Everyone below this employee, at any depth.
    @GetMapping("/{id}/reports")
    public ResponseEntity<PageResponse<EmployeeSummary>> allReports(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.allReports(id, paging.toPageable())));
    }

    // Direct manager first, up to the top of the organisation.
    @GetMapping("/{id}/reporting-chain")
    public ResponseEntity<List<EmployeeSummary>> reportingChain(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.reportingChain(id));
    }

    // ---- Department -----------------------------------------------------------

    @PutMapping("/{id}/department/{departmentId}")
    public ResponseEntity<EmployeeResponse> assignDepartment(
            @PathVariable("id") String id,
            @PathVariable("departmentId") String departmentId) {

        return ResponseEntity.ok(service.assignDepartment(id, departmentId));
    }

    @DeleteMapping("/{id}/department")
    public ResponseEntity<EmployeeResponse> removeDepartment(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeDepartment(id));
    }

    // ---- Office ---------------------------------------------------------------

    @PutMapping("/{id}/office/{officeId}")
    public ResponseEntity<EmployeeResponse> assignOffice(
            @PathVariable("id") String id,
            @PathVariable("officeId") String officeId) {

        return ResponseEntity.ok(service.assignOffice(id, officeId));
    }

    @DeleteMapping("/{id}/office")
    public ResponseEntity<EmployeeResponse> removeOffice(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.removeOffice(id));
    }

    // ---- Company and skills ---------------------------------------------------

    @PutMapping("/{id}/company/{companyId}")
    public ResponseEntity<EmployeeResponse> changeCompany(
            @PathVariable("id") String id,
            @PathVariable("companyId") String companyId) {

        return ResponseEntity.ok(service.changeCompany(id, companyId));
    }

    @PostMapping("/{id}/skills")
    public ResponseEntity<EmployeeResponse> addSkill(
            @PathVariable("id") String id,
            @RequestBody @Valid SkillRequest skill) {

        return ResponseEntity.ok(service.addSkill(id, skill));
    }

    @PutMapping("/{id}/skills")
    public ResponseEntity<EmployeeResponse> replaceSkills(
            @PathVariable("id") String id,
            @RequestBody List<@Valid SkillRequest> skills) {

        return ResponseEntity.ok(service.replaceSkills(id, skills));
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    public ResponseEntity<EmployeeResponse> removeSkill(
            @PathVariable("id") String id,
            @PathVariable("skillId") String skillId) {

        return ResponseEntity.ok(service.removeSkill(id, skillId));
    }
}
