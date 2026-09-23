package com.example.neo4j.controller;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateStaffingRequest;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.RejectStaffingRequest;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.service.StaffingService;

import jakarta.validation.Valid;

/*
 * Staffing approval workflow (access enforced in SecurityConfig):
 *   /projects/{id}/staffing-requests   the project's lead (or HR/ADMIN): raise and view requests
 *   /staffing-requests, approve, reject HR/ADMIN: the approval queue
 *   /staffing-requests/{id}/cancel      the requester (checked in StaffingService)
 */
@RestController
public class StaffingRequestController {

    private final StaffingService service;

    public StaffingRequestController(StaffingService service) {
        this.service = service;
    }

    // ---- Project lead -----------------------------------------------------------

    @PostMapping("/projects/{id}/staffing-requests")
    public ResponseEntity<StaffingRequestResponse> request(
            @PathVariable("id") String projectId,
            @RequestBody @Valid CreateStaffingRequest request,
            Authentication authentication) {

        return new ResponseEntity<>(service.request(projectId, request, authentication.getName()), HttpStatus.CREATED);
    }

    @GetMapping("/projects/{id}/staffing-requests")
    public ResponseEntity<PageResponse<StaffingRequestResponse>> projectRequests(
            @PathVariable("id") String projectId,
            @RequestParam(name = "status", required = false) StaffingRequestStatus status,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(projectId, status, paging.toPageable())));
    }

    @PostMapping("/staffing-requests/{requestId}/cancel")
    public ResponseEntity<StaffingRequestResponse> cancel(
            @PathVariable("requestId") String requestId,
            Authentication authentication) {

        return ResponseEntity.ok(service.cancel(requestId, authentication.getName()));
    }

    // ---- HR ---------------------------------------------------------------------

    // The approval queue: ?status=PENDING, oldest first.
    @GetMapping("/staffing-requests")
    public ResponseEntity<PageResponse<StaffingRequestResponse>> queue(
            @RequestParam(name = "status", required = false) StaffingRequestStatus status,
            @RequestParam(name = "projectId", required = false) String projectId,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(projectId, status, paging.toPageable())));
    }

    @GetMapping("/staffing-requests/{requestId}")
    public ResponseEntity<StaffingRequestResponse> getById(@PathVariable("requestId") String requestId) {
        return ResponseEntity.ok(service.findById(requestId));
    }

    @PostMapping("/staffing-requests/{requestId}/approve")
    public ResponseEntity<StaffingRequestResponse> approve(
            @PathVariable("requestId") String requestId,
            Authentication authentication) {

        return ResponseEntity.ok(service.approve(requestId, authentication.getName()));
    }

    @PostMapping("/staffing-requests/{requestId}/reject")
    public ResponseEntity<StaffingRequestResponse> reject(
            @PathVariable("requestId") String requestId,
            @RequestBody @Valid RejectStaffingRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(service.reject(requestId, request.reason(), authentication.getName()));
    }
}
