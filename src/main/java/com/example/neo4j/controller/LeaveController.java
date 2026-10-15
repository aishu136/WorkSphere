package com.example.neo4j.controller;

import java.time.LocalDate;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.dto.CreateLeaveRequest;
import com.example.neo4j.dto.LeaveBalanceResponse;
import com.example.neo4j.dto.LeaveFilter;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.RejectLeaveRequest;
import com.example.neo4j.service.LeaveService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/*
 * Leave workflow:
 *   POST /leave-requests, /mine, /{id}/cancel   any employee, for their own leave
 *   /team, /{id}/approve, /{id}/reject          a manager above the employee, or HR
 *   GET /leave-requests                         HR/ADMIN: everyone's leave (SecurityConfig)
 *   /employees/{id}/leave-balance               the employee, their managers, or HR (SecurityConfig)
 * Rules that depend on the request itself are checked in LeaveService.
 */
@RestController
public class LeaveController {

    private final LeaveService service;

    public LeaveController(LeaveService service) {
        this.service = service;
    }

    // ---- Employee ---------------------------------------------------------------

    @PostMapping("/leave-requests")
    public ResponseEntity<LeaveRequestResponse> request(
            @RequestBody @Valid CreateLeaveRequest request,
            Authentication authentication) {

        return new ResponseEntity<>(service.request(request, authentication.getName()), HttpStatus.CREATED);
    }

    @GetMapping("/leave-requests/mine")
    public ResponseEntity<PageResponse<LeaveRequestResponse>> mine(
            @ParameterObject LeaveFilter filter,
            @Valid @ParameterObject PageParams paging,
            Authentication authentication) {

        return ResponseEntity.ok(PageResponse.of(service.mine(authentication.getName(), filter, paging.toPageable())));
    }

    @PostMapping("/leave-requests/{requestId}/cancel")
    public ResponseEntity<LeaveRequestResponse> cancel(
            @PathVariable("requestId") String requestId,
            Authentication authentication) {

        return ResponseEntity.ok(service.cancel(requestId, authentication.getName()));
    }

    @GetMapping("/leave-requests/{requestId}")
    public ResponseEntity<LeaveRequestResponse> getById(
            @PathVariable("requestId") String requestId,
            Authentication authentication) {

        return ResponseEntity.ok(service.findById(requestId, authentication.getName()));
    }

    // ---- Manager or HR ----------------------------------------------------------

    // Everyone below the caller in the org chart, e.g. ?status=PENDING for the approval queue,
    // or ?status=APPROVED&from=2026-12-01&to=2026-12-31 for who is away.
    @GetMapping("/leave-requests/team")
    public ResponseEntity<PageResponse<LeaveRequestResponse>> team(
            @ParameterObject LeaveFilter filter,
            @Valid @ParameterObject PageParams paging,
            Authentication authentication) {

        return ResponseEntity.ok(PageResponse.of(service.team(authentication.getName(), filter, paging.toPageable())));
    }

    @GetMapping("/leave-requests")
    public ResponseEntity<PageResponse<LeaveRequestResponse>> search(
            @ParameterObject LeaveFilter filter,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(service.search(filter, paging.toPageable())));
    }

    @PostMapping("/leave-requests/{requestId}/approve")
    public ResponseEntity<LeaveRequestResponse> approve(
            @PathVariable("requestId") String requestId,
            Authentication authentication) {

        return ResponseEntity.ok(service.approve(requestId, authentication.getName()));
    }

    @PostMapping("/leave-requests/{requestId}/reject")
    public ResponseEntity<LeaveRequestResponse> reject(
            @PathVariable("requestId") String requestId,
            @RequestBody @Valid RejectLeaveRequest request,
            Authentication authentication) {

        return ResponseEntity.ok(service.reject(requestId, request.reason(), authentication.getName()));
    }

    // ---- Balance ----------------------------------------------------------------

    // Annual leave for a year, the current one by default.
    @GetMapping("/employees/{id}/leave-balance")
    public ResponseEntity<LeaveBalanceResponse> balance(
            @PathVariable("id") String employeeId,
            @RequestParam(name = "year", required = false) @Min(2000) @Max(2100) Integer year) {

        return ResponseEntity.ok(service.balance(employeeId, year == null ? LocalDate.now().getYear() : year));
    }
}
