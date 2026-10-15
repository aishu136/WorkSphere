package com.example.neo4j.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.SkillCount;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.dto.UpdateEmployeeRequest;
import com.example.neo4j.entity.Company;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.notification.EmployeeChangeEvent;
import com.example.neo4j.repository.CompanyRepository;
import com.example.neo4j.repository.DepartmentRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.LeaveQueries;
import com.example.neo4j.repository.OfficeRepository;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.SkillRepository;
import com.example.neo4j.security.CurrentUser;

/*
 * Every change is written to the audit log inside the same transaction, so a change and
 * its audit event are committed or rolled back together. Requests that change nothing
 * (e.g. adding a skill the employee already has) are not audited.
 */
@Service
public class EmployeeService {

	private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

	private final EmployeeRepository employeeRepository;
	private final EmployeeQueries employeeQueries;
	private final DepartmentRepository departmentRepository;
	private final SkillRepository skillRepository;
	private final CompanyRepository companyRepository;
	private final OfficeRepository officeRepository;
	private final ProjectQueries projectQueries;
	private final LeaveQueries leaveQueries;
	private final AuditLog auditLog;
	private final ApplicationEventPublisher events;

	public EmployeeService(EmployeeRepository employeeRepository, EmployeeQueries employeeQueries,
			DepartmentRepository departmentRepository, SkillRepository skillRepository,
			CompanyRepository companyRepository, OfficeRepository officeRepository, ProjectQueries projectQueries,
			LeaveQueries leaveQueries, AuditLog auditLog, ApplicationEventPublisher events) {
		this.employeeRepository = employeeRepository;
		this.employeeQueries = employeeQueries;
		this.departmentRepository = departmentRepository;
		this.skillRepository = skillRepository;
		this.companyRepository = companyRepository;
		this.officeRepository = officeRepository;
		this.projectQueries = projectQueries;
		this.leaveQueries = leaveQueries;
		this.auditLog = auditLog;
		this.events = events;
	}

	// ---- Directory ------------------------------------------------------------

	@Transactional(readOnly = true)
	public EmployeeResponse findById(String id) {
		return employeeQueries.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
	}

	@Transactional(readOnly = true)
	public Page<EmployeeResponse> search(EmployeeFilter filter, Pageable pageable) {
		if (filter.minSkillLevel() != null && (filter.skill() == null || filter.skill().isBlank())) {
			throw new IllegalArgumentException("minSkillLevel needs a skill");
		}
		return employeeQueries.search(filter, pageable);
	}

	// ---- Lifecycle ------------------------------------------------------------

	@Transactional
	public EmployeeResponse create(CreateEmployeeRequest request) {

		String code = request.employeeCode().trim().toUpperCase(Locale.ROOT);
		String email = normalizeEmail(request.email());

		if (employeeRepository.existsByEmployeeCode(code)) {
			throw new ConflictException("Employee code already exists: " + code);
		}
		if (employeeRepository.existsByEmail(email)) {
			throw new ConflictException("Email already in use: " + email);
		}

		Employee employee = new Employee();
		employee.setEmployeeCode(code);
		employee.setName(request.name().trim());
		employee.setEmail(email);
		employee.setJobTitle(trimToNull(request.jobTitle()));
		employee.setHireDate(request.hireDate());
		employee.setStatus(EmploymentStatus.ACTIVE);

		String id = employeeRepository.save(employee).getId();

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("employeeCode", code);
		details.put("name", employee.getName());
		details.put("email", email);
		details.put("jobTitle", employee.getJobTitle());
		details.put("hireDate", employee.getHireDate());
		audit(AuditAction.EMPLOYEE_CREATED, id, details);

		if (request.departmentId() != null) {
			assignDepartment(id, request.departmentId());
		}
		if (request.managerId() != null) {
			assignManager(id, request.managerId());
		}

		return findById(id);
	}

	@Transactional
	public EmployeeResponse update(String id, UpdateEmployeeRequest request) {

		Employee employee = requireEmployee(id);
		requireNotTerminated(employee);

		String name = request.name().trim();
		String email = normalizeEmail(request.email());
		String jobTitle = trimToNull(request.jobTitle());

		if (!email.equals(employee.getEmail()) && employeeRepository.existsByEmail(email)) {
			throw new ConflictException("Email already in use: " + email);
		}

		Changes changes = new Changes()
				.track("name", employee.getName(), name)
				.track("email", employee.getEmail(), email)
				.track("jobTitle", employee.getJobTitle(), jobTitle)
				.track("hireDate", employee.getHireDate(), request.hireDate());

		if (!changes.isEmpty()) {
			employee.setName(name);
			employee.setEmail(email);
			employee.setJobTitle(jobTitle);
			employee.setHireDate(request.hireDate());
			employeeRepository.save(employee);

			audit(AuditAction.EMPLOYEE_UPDATED, id, changes.asMap());
		}

		return findById(id);
	}

	// ACTIVE <-> ON_LEAVE only. Termination goes through terminate() so its checks always run.
	@Transactional
	public EmployeeResponse changeStatus(String id, EmploymentStatus status) {

		if (status == EmploymentStatus.TERMINATED) {
			throw new IllegalArgumentException("Use DELETE /employees/{id} to terminate an employee");
		}

		Employee employee = requireEmployee(id);
		requireNotTerminated(employee);

		if (employee.getStatus() != status) {
			Changes changes = new Changes().track("status", employee.getStatus(), status);

			employee.setStatus(status);
			employeeRepository.save(employee);

			audit(AuditAction.EMPLOYEE_STATUS_CHANGED, id, changes.asMap());
			publishChange(id, EmployeeChangeEvent.Type.STATUS_CHANGED, changes.asMap());
		}

		return findById(id);
	}

	/**
	 * Offboards an employee. The record is kept (status TERMINATED) rather than deleted;
	 * current reporting line, department, office and open project assignments are removed
	 * (finished projects are kept as history) and their login is disabled. Pending leave, and
	 * approved leave that hasn't started, is cancelled.
	 */
	@Transactional
	public EmployeeResponse terminate(String id) {

		Employee employee = requireEmployee(id);
		requireNotTerminated(employee);

		long directReports = employeeQueries.countDirectReports(id);
		if (directReports > 0) {
			throw new ConflictException("Reassign this employee's " + directReports
					+ " direct report(s) before terminating");
		}
		if (employeeQueries.headsAnyDepartment(id)) {
			throw new ConflictException("Assign a new department head before terminating this employee");
		}
		if (projectQueries.leadsOpenProject(id)) {
			throw new ConflictException("Assign a new lead to this employee's open projects before terminating");
		}

		EmployeeResponse before = findById(id);

		employee.setStatus(EmploymentStatus.TERMINATED);
		employee.setTerminationDate(LocalDate.now());
		employeeRepository.save(employee);

		employeeQueries.clearManager(id);
		employeeQueries.clearDepartment(id);
		employeeQueries.clearOffice(id);
		List<String> removedFromProjects = projectQueries.removeOpenAssignments(id);
		List<String> cancelledLeave = leaveQueries.cancelOpenRequests(id, CurrentUser.username(), Instant.now(),
				LocalDate.now());
		employeeQueries.disableLinkedLogin(id);

		// These links are removed, so record what they were.
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("previousStatus", before.status());
		details.put("terminationDate", employee.getTerminationDate());
		details.put("previousManagerId", managerId(before));
		details.put("previousDepartmentId", departmentId(before));
		details.put("previousOfficeId", before.office() == null ? null : before.office().id());
		details.put("removedFromProjectIds", removedFromProjects);
		details.put("cancelledLeaveRequestIds", cancelledLeave);
		audit(AuditAction.EMPLOYEE_TERMINATED, id, details);

		log.info("Terminated employee {}", id);
		return findById(id);
	}

	// ---- Reporting line -------------------------------------------------------

	@Transactional
	public EmployeeResponse assignManager(String id, String managerId) {

		if (id.equals(managerId)) {
			throw new IllegalArgumentException("An employee cannot be their own manager");
		}

		Employee employee = requireEmployee(id);
		requireNotTerminated(employee);

		Employee manager = employeeRepository.findById(managerId)
				.orElseThrow(() -> new ResourceNotFoundException("Manager not found with id: " + managerId));
		if (manager.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException("A terminated employee cannot be a manager");
		}

		EmployeeResponse before = findById(id);
		if (managerId.equals(managerId(before))) {
			return before;
		}

		if (employeeQueries.wouldCreateReportingCycle(id, managerId)) {
			throw new ConflictException("Reporting cycle: " + manager.getName()
					+ " already reports to " + employee.getName());
		}

		employeeQueries.setManager(id, managerId);
		Map<String, Object> managerChange = new Changes().track("managerId", managerId(before), managerId).asMap();
		audit(AuditAction.EMPLOYEE_MANAGER_CHANGED, id, managerChange);
		publishChange(id, EmployeeChangeEvent.Type.MANAGER_CHANGED, managerChange);

		return findById(id);
	}

	@Transactional
	public EmployeeResponse removeManager(String id) {

		EmployeeResponse before = findById(id);
		if (before.manager() == null) {
			return before;
		}

		employeeQueries.clearManager(id);
		audit(AuditAction.EMPLOYEE_MANAGER_CHANGED, id,
				new Changes().track("managerId", managerId(before), null).asMap());

		return findById(id);
	}

	@Transactional(readOnly = true)
	public Page<EmployeeSummary> directReports(String id, Pageable pageable) {
		requireExists(id);
		return employeeQueries.directReports(id, pageable);
	}

	@Transactional(readOnly = true)
	public Page<EmployeeSummary> allReports(String id, Pageable pageable) {
		requireExists(id);
		return employeeQueries.allReports(id, pageable);
	}

	@Transactional(readOnly = true)
	public List<EmployeeSummary> reportingChain(String id) {
		requireExists(id);
		return employeeQueries.reportingChain(id);
	}

	@Transactional(readOnly = true)
	public Page<SkillCount> skills(String nameContains, Pageable pageable) {
		return employeeQueries.skills(nameContains, pageable);
	}

	// ---- Department -----------------------------------------------------------

	@Transactional
	public EmployeeResponse assignDepartment(String id, String departmentId) {

		requireNotTerminated(requireEmployee(id));

		if (!departmentRepository.existsById(departmentId)) {
			throw new ResourceNotFoundException("Department not found with id: " + departmentId);
		}

		EmployeeResponse before = findById(id);
		if (departmentId.equals(departmentId(before))) {
			return before;
		}

		employeeQueries.setDepartment(id, departmentId);
		audit(AuditAction.EMPLOYEE_DEPARTMENT_CHANGED, id,
				new Changes().track("departmentId", departmentId(before), departmentId).asMap());

		return findById(id);
	}

	@Transactional
	public EmployeeResponse removeDepartment(String id) {

		EmployeeResponse before = findById(id);
		if (before.department() == null) {
			return before;
		}

		employeeQueries.clearDepartment(id);
		audit(AuditAction.EMPLOYEE_DEPARTMENT_CHANGED, id,
				new Changes().track("departmentId", departmentId(before), null).asMap());

		return findById(id);
	}

	// ---- Office ---------------------------------------------------------------

	@Transactional
	public EmployeeResponse assignOffice(String id, String officeId) {

		requireNotTerminated(requireEmployee(id));

		if (!officeRepository.existsById(officeId)) {
			throw new ResourceNotFoundException("Office not found with id: " + officeId);
		}

		EmployeeResponse before = findById(id);
		String previous = before.office() == null ? null : before.office().id();
		if (officeId.equals(previous)) {
			return before;
		}

		employeeQueries.setOffice(id, officeId);
		audit(AuditAction.EMPLOYEE_OFFICE_CHANGED, id, new Changes().track("officeId", previous, officeId).asMap());

		return findById(id);
	}

	@Transactional
	public EmployeeResponse removeOffice(String id) {

		EmployeeResponse before = findById(id);
		if (before.office() == null) {
			return before;
		}

		employeeQueries.clearOffice(id);
		audit(AuditAction.EMPLOYEE_OFFICE_CHANGED, id,
				new Changes().track("officeId", before.office().id(), null).asMap());

		return findById(id);
	}

	// ---- Company and skills ---------------------------------------------------

	@Transactional
	public EmployeeResponse changeCompany(String id, String companyId) {

		Employee employee = requireEmployee(id);
		requireNotTerminated(employee);

		Company company = companyRepository.findById(companyId)
				.orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));

		String previousCompanyId = employee.getCompany() == null ? null : employee.getCompany().getId();
		if (!companyId.equals(previousCompanyId)) {
			employee.setCompany(company);
			employeeRepository.save(employee);

			audit(AuditAction.EMPLOYEE_COMPANY_CHANGED, id,
					new Changes().track("companyId", previousCompanyId, companyId).asMap());
		}

		return findById(id);
	}

	// Adds a skill, or changes its level if the employee already has it.
	@Transactional
	public EmployeeResponse addSkill(String id, SkillRequest skill) {

		requireExists(id);
		List<SkillResponse> before = employeeQueries.skillsOf(id);
		applySkills(id, before, SkillChanges.added(before, skill, skillRepository));

		return findById(id);
	}

	@Transactional
	public EmployeeResponse replaceSkills(String id, List<SkillRequest> skills) {

		requireExists(id);
		List<SkillResponse> before = employeeQueries.skillsOf(id);
		applySkills(id, before, SkillChanges.replaced(before, skills, skillRepository));

		return findById(id);
	}

	@Transactional
	public EmployeeResponse removeSkill(String id, String skillId) {

		requireExists(id);
		List<SkillResponse> before = employeeQueries.skillsOf(id);
		applySkills(id, before, SkillChanges.without(before, skillId));

		return findById(id);
	}

	// ---- Helpers --------------------------------------------------------------

	private void audit(AuditAction action, String employeeId, Map<String, Object> details) {
		auditLog.record(action, AuditTargetType.EMPLOYEE, employeeId, details);
	}

	// Writes only what changed; a request that changes nothing is not audited or announced.
	private void applySkills(String employeeId, List<SkillResponse> before, List<SkillResponse> after) {

		SkillChanges changes = SkillChanges.between(before, after);
		if (changes.isEmpty()) {
			return;
		}

		employeeQueries.removeSkills(employeeId, changes.removedIds());
		changes.toWrite().forEach(skill -> employeeQueries.setSkill(employeeId, skill.id(), skill.level()));

		Map<String, Object> details = changes.details();
		audit(AuditAction.EMPLOYEE_SKILLS_CHANGED, employeeId, details);
		publishChange(employeeId, EmployeeChangeEvent.Type.SKILLS_CHANGED, details);
	}

	// Notifications decide from selfService whether anyone should be emailed (see EmployeeChangeNotifier).
	private void publishChange(String employeeId, EmployeeChangeEvent.Type type, Map<String, Object> details) {
		events.publishEvent(new EmployeeChangeEvent(employeeId, type, details, CurrentUser.username(),
				CurrentUser.isSelfService()));
	}

	private static String managerId(EmployeeResponse employee) {
		EmployeeSummary manager = employee.manager();
		return manager == null ? null : manager.id();
	}

	private static String departmentId(EmployeeResponse employee) {
		DepartmentSummary department = employee.department();
		return department == null ? null : department.id();
	}

	private Employee requireEmployee(String id) {
		return employeeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
	}

	private void requireExists(String id) {
		if (!employeeRepository.existsById(id)) {
			throw new ResourceNotFoundException("Employee not found with id: " + id);
		}
	}

	private static void requireNotTerminated(Employee employee) {
		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException("Employee " + employee.getId() + " is terminated");
		}
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
