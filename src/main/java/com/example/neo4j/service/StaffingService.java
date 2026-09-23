package com.example.neo4j.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateStaffingRequest;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.Project;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequest;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.ProjectRepository;
import com.example.neo4j.repository.StaffingQueries;
import com.example.neo4j.repository.StaffingRequestRepository;

/*
 * Staffing approval workflow: a project lead requests a change to their project's members,
 * HR approves or rejects it.
 *
 * - Requests are validated when created, so HR doesn't review requests that could never be applied.
 * - Approval applies the change through ProjectService, so every rule (open project, active
 *   employee, 100% allocation cap) is checked again against the current state.
 * - Nobody can approve their own request.
 * - Every step is recorded in the project's audit history.
 *
 * Who may call what (lead vs HR) is enforced in SecurityConfig; this class enforces the rules
 * that depend on the request itself, such as "only the requester can cancel".
 */
@Service
public class StaffingService {

	private final StaffingRequestRepository requestRepository;
	private final StaffingQueries staffingQueries;
	private final ProjectRepository projectRepository;
	private final ProjectQueries projectQueries;
	private final EmployeeRepository employeeRepository;
	private final ProjectService projectService;
	private final AuditLog auditLog;

	public StaffingService(StaffingRequestRepository requestRepository, StaffingQueries staffingQueries,
			ProjectRepository projectRepository, ProjectQueries projectQueries, EmployeeRepository employeeRepository,
			ProjectService projectService, AuditLog auditLog) {
		this.requestRepository = requestRepository;
		this.staffingQueries = staffingQueries;
		this.projectRepository = projectRepository;
		this.projectQueries = projectQueries;
		this.employeeRepository = employeeRepository;
		this.projectService = projectService;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	public StaffingRequestResponse findById(String requestId) {
		return staffingQueries.findById(requestId)
				.orElseThrow(() -> new ResourceNotFoundException("Staffing request not found with id: " + requestId));
	}

	@Transactional(readOnly = true)
	public Page<StaffingRequestResponse> search(String projectId, StaffingRequestStatus status, Pageable pageable) {
		return staffingQueries.search(projectId, status, pageable);
	}

	// ---- Lead --------------------------------------------------------------------

	@Transactional
	public StaffingRequestResponse request(String projectId, CreateStaffingRequest request, String requestedBy) {

		Project project = projectRepository.findById(projectId)
				.orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));
		if (!project.getStatus().isOpen()) {
			throw new ConflictException("Project is " + project.getStatus() + "; staffing can only change on open projects");
		}

		Employee employee = employeeRepository.findById(request.employeeId())
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + request.employeeId()));

		if (requestRepository.existsByProjectIdAndEmployeeIdAndStatus(projectId, employee.getId(),
				StaffingRequestStatus.PENDING)) {
			throw new ConflictException("There is already a pending request for " + employee.getName()
					+ " on this project");
		}

		boolean onProject = projectQueries.member(projectId, employee.getId()).isPresent();

		if (request.action() == StaffingAction.ASSIGN) {
			if (request.allocationPercent() == null) {
				throw new IllegalArgumentException("allocationPercent is required to assign someone");
			}
			if (employee.getStatus() == EmploymentStatus.TERMINATED) {
				throw new ConflictException(employee.getName() + " is terminated");
			}
			int elsewhere = projectQueries.openAllocationExcluding(employee.getId(), projectId);
			if (elsewhere + request.allocationPercent() > ProjectService.MAX_ALLOCATION) {
				throw new ConflictException(employee.getName() + " is already " + elsewhere + "% allocated; adding "
						+ request.allocationPercent() + "% would exceed " + ProjectService.MAX_ALLOCATION + "%");
			}
		} else if (!onProject) {
			throw new ConflictException(employee.getName() + " is not on this project");
		}

		StaffingRequest staffingRequest = new StaffingRequest();
		staffingRequest.setProjectId(projectId);
		staffingRequest.setEmployeeId(employee.getId());
		staffingRequest.setAction(request.action());
		if (request.action() == StaffingAction.ASSIGN) {
			staffingRequest.setRole(trimToNull(request.role()));
			staffingRequest.setAllocationPercent(request.allocationPercent());
		}
		staffingRequest.setStatus(StaffingRequestStatus.PENDING);
		staffingRequest.setRequestedBy(requestedBy);
		staffingRequest.setRequestedAt(Instant.now());

		StaffingRequest saved = requestRepository.save(staffingRequest);
		audit(AuditAction.STAFFING_REQUESTED, saved, null);

		return findById(saved.getId());
	}

	/** Only the person who raised the request can withdraw it. */
	@Transactional
	public StaffingRequestResponse cancel(String requestId, String username) {

		StaffingRequest request = requirePending(requestId);
		if (!request.getRequestedBy().equals(username)) {
			throw new AccessDeniedException("Only the requester can cancel this request");
		}

		decide(request, StaffingRequestStatus.CANCELLED, username, null);
		audit(AuditAction.STAFFING_REQUEST_CANCELLED, request, null);

		return findById(requestId);
	}

	// ---- HR ----------------------------------------------------------------------

	/** Applies the change. If a rule now fails (e.g. over 100%), the request stays pending. */
	@Transactional
	public StaffingRequestResponse approve(String requestId, String username) {

		StaffingRequest request = requirePending(requestId);
		if (request.getRequestedBy().equals(username)) {
			throw new ConflictException("You can't approve your own request; another HR user must decide it");
		}

		if (request.getAction() == StaffingAction.ASSIGN) {
			projectService.assign(request.getProjectId(), request.getEmployeeId(),
					new AssignmentRequest(request.getRole(), request.getAllocationPercent()));
		} else {
			projectService.unassign(request.getProjectId(), request.getEmployeeId());
		}

		decide(request, StaffingRequestStatus.APPROVED, username, null);
		audit(AuditAction.STAFFING_REQUEST_APPROVED, request, null);

		return findById(requestId);
	}

	@Transactional
	public StaffingRequestResponse reject(String requestId, String reason, String username) {

		StaffingRequest request = requirePending(requestId);

		decide(request, StaffingRequestStatus.REJECTED, username, reason.trim());
		audit(AuditAction.STAFFING_REQUEST_REJECTED, request, reason.trim());

		return findById(requestId);
	}

	// ---- Helpers -----------------------------------------------------------------

	private StaffingRequest requirePending(String requestId) {
		StaffingRequest request = requestRepository.findById(requestId)
				.orElseThrow(() -> new ResourceNotFoundException("Staffing request not found with id: " + requestId));
		if (request.getStatus() != StaffingRequestStatus.PENDING) {
			throw new ConflictException("Request is already " + request.getStatus());
		}
		return request;
	}

	// Saving checks the version, so a concurrent decision on the same request fails instead of both applying.
	private void decide(StaffingRequest request, StaffingRequestStatus status, String username, String reason) {
		request.setStatus(status);
		request.setDecidedBy(username);
		request.setDecidedAt(Instant.now());
		request.setReason(reason);
		requestRepository.save(request);
	}

	// Recorded on the project, so the project's history shows the whole workflow.
	private void audit(AuditAction action, StaffingRequest request, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("requestId", request.getId());
		details.put("employeeId", request.getEmployeeId());
		details.put("action", request.getAction());
		if (request.getAction() == StaffingAction.ASSIGN) {
			details.put("role", request.getRole());
			details.put("allocationPercent", request.getAllocationPercent());
		}
		details.put("requestedBy", request.getRequestedBy());
		if (reason != null) {
			details.put("reason", reason);
		}
		auditLog.record(action, AuditTargetType.PROJECT, request.getProjectId(), details);
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
