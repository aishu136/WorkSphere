package com.example.neo4j.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.DepartmentResponse;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.UpdateDepartmentRequest;
import com.example.neo4j.entity.Department;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.DepartmentRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;

@Service
public class DepartmentService {

	private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

	private final DepartmentRepository departmentRepository;
	private final DepartmentQueries departmentQueries;
	private final EmployeeRepository employeeRepository;
	private final EmployeeQueries employeeQueries;
	private final AuditLog auditLog;

	public DepartmentService(DepartmentRepository departmentRepository, DepartmentQueries departmentQueries,
			EmployeeRepository employeeRepository, EmployeeQueries employeeQueries, AuditLog auditLog) {
		this.departmentRepository = departmentRepository;
		this.departmentQueries = departmentQueries;
		this.employeeRepository = employeeRepository;
		this.employeeQueries = employeeQueries;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	public DepartmentResponse findById(String id) {
		return departmentQueries.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Department not found with id: " + id));
	}

	@Transactional(readOnly = true)
	public Page<DepartmentResponse> findAll(boolean topLevelOnly, Pageable pageable) {
		return departmentQueries.findAll(topLevelOnly, pageable);
	}

	@Transactional(readOnly = true)
	public Page<DepartmentResponse> subDepartments(String id, Pageable pageable) {
		requireExists(id);
		return departmentQueries.subDepartments(id, pageable);
	}

	@Transactional(readOnly = true)
	public Page<EmployeeResponse> members(String id, boolean includeSubDepartments, Pageable pageable) {
		requireExists(id);
		return employeeQueries.departmentMembers(id, includeSubDepartments, pageable);
	}

	@Transactional
	public DepartmentResponse create(CreateDepartmentRequest request) {

		String code = request.code().trim().toUpperCase(Locale.ROOT);
		if (departmentRepository.existsByCode(code)) {
			throw new ConflictException("Department code already exists: " + code);
		}

		Department department = new Department();
		department.setCode(code);
		department.setName(request.name().trim());
		department.setDescription(trimToNull(request.description()));

		String id = departmentRepository.save(department).getId();

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", code);
		details.put("name", department.getName());
		details.put("description", department.getDescription());
		audit(AuditAction.DEPARTMENT_CREATED, id, details);

		if (request.parentId() != null) {
			setParent(id, request.parentId());
		}

		return findById(id);
	}

	@Transactional
	public DepartmentResponse update(String id, UpdateDepartmentRequest request) {

		Department department = departmentRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Department not found with id: " + id));

		String name = request.name().trim();
		String description = trimToNull(request.description());

		Changes changes = new Changes()
				.track("name", department.getName(), name)
				.track("description", department.getDescription(), description);

		if (!changes.isEmpty()) {
			department.setName(name);
			department.setDescription(description);
			departmentRepository.save(department);

			audit(AuditAction.DEPARTMENT_UPDATED, id, changes.asMap());
		}

		return findById(id);
	}

	// Only empty departments can be deleted, so no employee or sub-department is left orphaned.
	@Transactional
	public void delete(String id) {

		requireExists(id);

		if (departmentQueries.hasMembersOrSubDepartments(id)) {
			throw new ConflictException("Move this department's employees and sub-departments before deleting it");
		}

		// The department is gone afterwards, so record what it was.
		DepartmentResponse deleted = findById(id);
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", deleted.code());
		details.put("name", deleted.name());
		details.put("parentId", deleted.parent() == null ? null : deleted.parent().id());

		departmentRepository.deleteById(id);
		audit(AuditAction.DEPARTMENT_DELETED, id, details);

		log.info("Deleted department {}", id);
	}

	@Transactional
	public DepartmentResponse setParent(String id, String parentId) {

		if (id.equals(parentId)) {
			throw new IllegalArgumentException("A department cannot be its own parent");
		}

		requireExists(id);
		if (!departmentRepository.existsById(parentId)) {
			throw new ResourceNotFoundException("Parent department not found with id: " + parentId);
		}

		DepartmentResponse before = findById(id);
		String previousParentId = before.parent() == null ? null : before.parent().id();
		if (parentId.equals(previousParentId)) {
			return before;
		}

		if (departmentQueries.wouldCreateCycle(id, parentId)) {
			throw new ConflictException("Department cycle: the proposed parent is already below this department");
		}

		departmentQueries.setParent(id, parentId);
		audit(AuditAction.DEPARTMENT_PARENT_CHANGED, id,
				new Changes().track("parentId", previousParentId, parentId).asMap());

		return findById(id);
	}

	@Transactional
	public DepartmentResponse removeParent(String id) {

		DepartmentResponse before = findById(id);
		if (before.parent() == null) {
			return before;
		}

		departmentQueries.clearParent(id);
		audit(AuditAction.DEPARTMENT_PARENT_CHANGED, id,
				new Changes().track("parentId", before.parent().id(), null).asMap());

		return findById(id);
	}

	@Transactional
	public DepartmentResponse setHead(String id, String employeeId) {

		requireExists(id);

		Employee employee = employeeRepository.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException("A terminated employee cannot head a department");
		}

		DepartmentResponse before = findById(id);
		String previousHeadId = before.head() == null ? null : before.head().id();
		if (employeeId.equals(previousHeadId)) {
			return before;
		}

		departmentQueries.setHead(id, employeeId);
		audit(AuditAction.DEPARTMENT_HEAD_CHANGED, id,
				new Changes().track("headEmployeeId", previousHeadId, employeeId).asMap());

		return findById(id);
	}

	@Transactional
	public DepartmentResponse removeHead(String id) {

		DepartmentResponse before = findById(id);
		if (before.head() == null) {
			return before;
		}

		departmentQueries.clearHead(id);
		audit(AuditAction.DEPARTMENT_HEAD_CHANGED, id,
				new Changes().track("headEmployeeId", before.head().id(), null).asMap());

		return findById(id);
	}

	private void audit(AuditAction action, String departmentId, Map<String, Object> details) {
		auditLog.record(action, AuditTargetType.DEPARTMENT, departmentId, details);
	}

	private void requireExists(String id) {
		if (!departmentRepository.existsById(id)) {
			throw new ResourceNotFoundException("Department not found with id: " + id);
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
