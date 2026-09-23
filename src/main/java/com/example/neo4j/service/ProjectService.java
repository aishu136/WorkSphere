package com.example.neo4j.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeAssignmentResponse;
import com.example.neo4j.dto.EmployeeProjectsResponse;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.UpdateProjectRequest;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.Project;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.DepartmentRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.ProjectRepository;

/*
 * Projects and staffing. The key rule: an employee's allocation across open projects
 * (PLANNED, ACTIVE, ON_HOLD) can never exceed 100%. It is checked when someone is assigned
 * or their allocation changes, and when a finished project is reopened.
 */
@Service
public class ProjectService {

	static final int MAX_ALLOCATION = 100;

	private final ProjectRepository projectRepository;
	private final ProjectQueries projectQueries;
	private final EmployeeRepository employeeRepository;
	private final DepartmentRepository departmentRepository;
	private final AuditLog auditLog;

	public ProjectService(ProjectRepository projectRepository, ProjectQueries projectQueries,
			EmployeeRepository employeeRepository, DepartmentRepository departmentRepository, AuditLog auditLog) {
		this.projectRepository = projectRepository;
		this.projectQueries = projectQueries;
		this.employeeRepository = employeeRepository;
		this.departmentRepository = departmentRepository;
		this.auditLog = auditLog;
	}

	// ---- Reads ----------------------------------------------------------------

	@Transactional(readOnly = true)
	public ProjectResponse findById(String id) {
		return projectQueries.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
	}

	@Transactional(readOnly = true)
	public Page<ProjectResponse> search(String name, ProjectStatus status, String departmentId, Pageable pageable) {
		return projectQueries.search(name, status, departmentId, pageable);
	}

	@Transactional(readOnly = true)
	public Page<ProjectMemberResponse> members(String id, Pageable pageable) {
		requireProject(id);
		return projectQueries.members(id, pageable);
	}

	@Transactional(readOnly = true)
	public EmployeeProjectsResponse projectsOf(String employeeId) {

		if (!employeeRepository.existsById(employeeId)) {
			throw new ResourceNotFoundException("Employee not found with id: " + employeeId);
		}

		List<EmployeeAssignmentResponse> assignments = projectQueries.assignmentsOf(employeeId);
		int allocation = assignments.stream()
				.filter(a -> a.project().status().isOpen())
				.mapToInt(EmployeeAssignmentResponse::allocationPercent)
				.sum();

		return new EmployeeProjectsResponse(allocation, assignments);
	}

	// ---- Lifecycle ------------------------------------------------------------

	@Transactional
	public ProjectResponse create(CreateProjectRequest request) {

		String code = request.code().trim().toUpperCase(Locale.ROOT);
		if (projectRepository.existsByCode(code)) {
			throw new ConflictException("Project code already exists: " + code);
		}
		requireValidDates(request.startDate(), request.endDate());

		Project project = new Project();
		project.setCode(code);
		project.setName(request.name().trim());
		project.setDescription(trimToNull(request.description()));
		project.setStatus(request.status() == null ? ProjectStatus.PLANNED : request.status());
		project.setStartDate(request.startDate());
		project.setEndDate(request.endDate());

		String id = projectRepository.save(project).getId();

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", code);
		details.put("name", project.getName());
		details.put("status", project.getStatus());
		details.put("startDate", project.getStartDate());
		details.put("endDate", project.getEndDate());
		audit(AuditAction.PROJECT_CREATED, id, details);

		if (request.departmentId() != null) {
			setDepartment(id, request.departmentId());
		}
		if (request.leadId() != null) {
			setLead(id, request.leadId());
		}

		return findById(id);
	}

	@Transactional
	public ProjectResponse update(String id, UpdateProjectRequest request) {

		Project project = requireProject(id);
		requireValidDates(request.startDate(), request.endDate());

		String name = request.name().trim();
		String description = trimToNull(request.description());

		Changes changes = new Changes()
				.track("name", project.getName(), name)
				.track("description", project.getDescription(), description)
				.track("startDate", project.getStartDate(), request.startDate())
				.track("endDate", project.getEndDate(), request.endDate());

		if (!changes.isEmpty()) {
			project.setName(name);
			project.setDescription(description);
			project.setStartDate(request.startDate());
			project.setEndDate(request.endDate());
			projectRepository.save(project);

			audit(AuditAction.PROJECT_UPDATED, id, changes.asMap());
		}

		return findById(id);
	}

	@Transactional
	public ProjectResponse changeStatus(String id, ProjectStatus status) {

		Project project = requireProject(id);
		ProjectStatus previous = project.getStatus();
		if (previous == status) {
			return findById(id);
		}

		// Reopening a finished project makes its members' allocation count again.
		if (status.isOpen() && !previous.isOpen()) {
			List<String> overAllocated = projectQueries.membersOverAllocatedIfReopened(id);
			if (!overAllocated.isEmpty()) {
				throw new ConflictException("Reopening would put these members over 100% allocation: "
						+ String.join(", ", overAllocated));
			}
		}

		project.setStatus(status);
		projectRepository.save(project);
		audit(AuditAction.PROJECT_STATUS_CHANGED, id, new Changes().track("status", previous, status).asMap());

		return findById(id);
	}

	// Projects with any assignments (even finished ones) are kept as history: cancel them instead.
	@Transactional
	public void delete(String id) {

		ProjectResponse project = findById(id);

		if (projectQueries.hasMembers(id)) {
			throw new ConflictException("This project has members. Set its status to CANCELLED instead of deleting it");
		}

		projectRepository.deleteById(id);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", project.code());
		details.put("name", project.name());
		details.put("status", project.status());
		audit(AuditAction.PROJECT_DELETED, id, details);
	}

	// ---- Lead and department --------------------------------------------------

	@Transactional
	public ProjectResponse setLead(String id, String employeeId) {

		requireProject(id);
		requireActiveEmployee(employeeId, "A terminated employee cannot lead a project");

		ProjectResponse before = findById(id);
		String previous = before.lead() == null ? null : before.lead().id();
		if (employeeId.equals(previous)) {
			return before;
		}

		projectQueries.setLead(id, employeeId);
		audit(AuditAction.PROJECT_LEAD_CHANGED, id, new Changes().track("leadEmployeeId", previous, employeeId).asMap());

		return findById(id);
	}

	@Transactional
	public ProjectResponse removeLead(String id) {

		ProjectResponse before = findById(id);
		if (before.lead() == null) {
			return before;
		}

		projectQueries.clearLead(id);
		audit(AuditAction.PROJECT_LEAD_CHANGED, id,
				new Changes().track("leadEmployeeId", before.lead().id(), null).asMap());

		return findById(id);
	}

	@Transactional
	public ProjectResponse setDepartment(String id, String departmentId) {

		requireProject(id);
		if (!departmentRepository.existsById(departmentId)) {
			throw new ResourceNotFoundException("Department not found with id: " + departmentId);
		}

		ProjectResponse before = findById(id);
		String previous = before.department() == null ? null : before.department().id();
		if (departmentId.equals(previous)) {
			return before;
		}

		projectQueries.setDepartment(id, departmentId);
		audit(AuditAction.PROJECT_DEPARTMENT_CHANGED, id,
				new Changes().track("departmentId", previous, departmentId).asMap());

		return findById(id);
	}

	@Transactional
	public ProjectResponse removeDepartment(String id) {

		ProjectResponse before = findById(id);
		if (before.department() == null) {
			return before;
		}

		projectQueries.clearDepartment(id);
		audit(AuditAction.PROJECT_DEPARTMENT_CHANGED, id,
				new Changes().track("departmentId", before.department().id(), null).asMap());

		return findById(id);
	}

	// ---- Members --------------------------------------------------------------

	/** Adds someone to the project, or changes their role or allocation. */
	@Transactional
	public ProjectMemberResponse assign(String id, String employeeId, AssignmentRequest request) {

		Project project = requireProject(id);
		if (!project.getStatus().isOpen()) {
			throw new ConflictException("Project is " + project.getStatus() + "; people can only join open projects");
		}
		Employee employee = requireActiveEmployee(employeeId, "A terminated employee cannot join a project");

		int allocation = request.allocationPercent();
		int elsewhere = projectQueries.openAllocationExcluding(employeeId, id);
		if (elsewhere + allocation > MAX_ALLOCATION) {
			throw new ConflictException(employee.getName() + " is already " + elsewhere
					+ "% allocated; adding " + allocation + "% would exceed " + MAX_ALLOCATION + "%");
		}

		String role = trimToNull(request.role());
		Optional<ProjectMemberResponse> previous = projectQueries.member(id, employeeId);

		projectQueries.assign(employeeId, id, role, allocation);

		if (previous.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("employeeId", employeeId);
			details.put("role", role);
			details.put("allocationPercent", allocation);
			audit(AuditAction.PROJECT_MEMBER_ADDED, id, details);
		} else {
			Changes changes = new Changes()
					.track("role", previous.get().role(), role)
					.track("allocationPercent", previous.get().allocationPercent(), allocation);
			if (!changes.isEmpty()) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("employeeId", employeeId);
				details.putAll(changes.asMap());
				audit(AuditAction.PROJECT_MEMBER_UPDATED, id, details);
			}
		}

		return projectQueries.member(id, employeeId).orElseThrow();
	}

	@Transactional
	public void unassign(String id, String employeeId) {

		requireProject(id);
		Optional<ProjectMemberResponse> previous = projectQueries.member(id, employeeId);
		if (previous.isEmpty()) {
			return;
		}

		projectQueries.unassign(employeeId, id);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("employeeId", employeeId);
		details.put("role", previous.get().role());
		details.put("allocationPercent", previous.get().allocationPercent());
		audit(AuditAction.PROJECT_MEMBER_REMOVED, id, details);
	}

	// ---- Helpers --------------------------------------------------------------

	private void audit(AuditAction action, String projectId, Map<String, Object> details) {
		auditLog.record(action, AuditTargetType.PROJECT, projectId, details);
	}

	private Project requireProject(String id) {
		return projectRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
	}

	private Employee requireActiveEmployee(String employeeId, String terminatedMessage) {
		Employee employee = employeeRepository.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException(terminatedMessage);
		}
		return employee;
	}

	private static void requireValidDates(LocalDate start, LocalDate end) {
		if (start != null && end != null && end.isBefore(start)) {
			throw new IllegalArgumentException("End date cannot be before start date");
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
