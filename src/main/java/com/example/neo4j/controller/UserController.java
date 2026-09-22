package com.example.neo4j.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateUserRequest;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.UserResponse;
import com.example.neo4j.service.UserService;

import jakarta.validation.Valid;

// ADMIN only - enforced in SecurityConfig.
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@RequestBody @Valid CreateUserRequest request) {
        return new ResponseEntity<>(UserResponse.from(service.create(request)), HttpStatus.CREATED);
    }

    // Links a login to an employee, which gives it manager rights over that employee's team.
    @PutMapping("/{id}/employee/{employeeId}")
    public ResponseEntity<UserResponse> linkEmployee(
            @PathVariable("id") String id,
            @PathVariable("employeeId") String employeeId) {

        return ResponseEntity.ok(UserResponse.from(service.linkEmployee(id, employeeId)));
    }

    @DeleteMapping("/{id}/employee")
    public ResponseEntity<UserResponse> unlinkEmployee(@PathVariable("id") String id) {
        return ResponseEntity.ok(UserResponse.from(service.unlinkEmployee(id)));
    }

    @GetMapping
    public ResponseEntity<PageResponse<UserResponse>> getAll(
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.from(
                service.findAll(paging.toPageable()), UserResponse::from));
    }
}
