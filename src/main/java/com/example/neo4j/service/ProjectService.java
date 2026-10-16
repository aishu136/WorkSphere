package com.example.neo4j.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CandidateResponse;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeAssignmentResponse;
import com.example.neo4j.dto.EmployeeProjectsResponse;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.dto.UpdateProjectRequest;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.Project;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.DepartmentRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.LeaveQueries.Period;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.ProjectQueries.Booking;
import com.example.neo4j.repository.ProjectQueries.MemberBooking;
import com.example.neo4j.repository.ProjectRepository;
import com.example.neo4j.repository.SkillRepository;
import com.example.neo4j.service.Allocations.Peak;

/*
 * Projects and staffing. The key rule: on any day, an employee's allocation across open projects
 * (PLANNED, ACTIVE, ON_HOLD) can never exceed 100%. Assignments have dates, so someone fully
 * booked until March can be booked from April. It is checked when someone is assigned or their
 * allocation or dates change, and when a finished project is reopened.
 */
@Service
public class ProjectService {

	static final int MAX_ALLOCATION = 100;

	// Candidate window when the project has no end date.
	static final int DEFAULT_WINDOW_DAYS = 90;

	private final ProjectRepository projectRepository;
	private final ProjectQueries projectQueries;
	private final EmployeeRepository employeeRepository;
	private final DepartmentRepository departmentRepository;
	private final SkillRepository skillRepository;
	private final AuditLog auditLog;

	public ProjectService(ProjectRepository projectRepository, ProjectQueries projectQueries,
			EmployeeRepository employeeRepository, DepartmentRepository departmentRepository,
			SkillRepository skillRepository, AuditLog auditLog) {
		this.projectRepository = projectRepository;
		this.projectQueries = projectQueries;
		this.employeeRepository = employeeRepository;
		this.departmentRepository = departmentRepository;
		this.skillRepository = skillRepository;
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
		LocalDate today = LocalDate.now();
		int allocation = Allocations.loadOn(assignments.stream()
				.filter(a -> a.project().status().isOpen())
				.map(a -> new Booking(a.startDate(), a.endDate(), a.allocationPercent()))
				.toList(), today);

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
			List<String> overAllocated = projectQueries.memberBookings(id).stream()
					.filter(member -> {
						Booking booking = member.booking();
						Peak elsewhere = Allocations.peak(projectQueries.bookingsExcluding(member.employeeId(), id),
								booking.startDate(), booking.endDate());
						return elsewhere.percent() + booking.allocationPercent() > MAX_ALLOCATION;
					})
					.map(MemberBooking::name)
					.toList();
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

	// ---- Required skills and matching -----------------------------------------

	// Adds a required skill, or changes its minimum level if the project already requires it.
	@Transactional
	public ProjectResponse addRequiredSkill(String id, SkillRequest skill) {

		requireProject(id);
		List<SkillResponse> before = projectQueries.requiredSkillsOf(id);
		applyRequiredSkills(id, before, SkillChanges.added(before, skill, skillRepository));

		return findById(id);
	}

	@Transactional
	public ProjectResponse replaceRequiredSkills(String id, List<SkillRequest> skills) {

		requireProject(id);
		List<SkillResponse> before = projectQueries.requiredSkillsOf(id);
		applyRequiredSkills(id, before, SkillChanges.replaced(before, skills, skillRepository));

		return findById(id);
	}

	@Transactional
	public ProjectResponse removeRequiredSkill(String id, String skillId) {

		requireProject(id);
		List<SkillResponse> before = projectQueries.requiredSkillsOf(id);
		applyRequiredSkills(id, before, SkillChanges.without(before, skillId));

		return findById(id);
	}

	/**
	 * People who could join the project between from and to, best match first. Only active
	 * employees who aren't on the project yet, have at least one required skill, have at least
	 * allocationPercent free on their busiest day in the window, and aren't on approved leave for
	 * all of it. Empty when the project requires no skills.
	 *
	 * Order: skill match, then people in the owning department, then how much of the window they
	 * are not on leave, then free capacity.
	 *
	 * The window defaults to the project's dates, starting no earlier than today, or
	 * DEFAULT_WINDOW_DAYS when the project has no end date.
	 */
	@Transactional(readOnly = true)
	public Page<CandidateResponse> candidates(String id, int allocationPercent, LocalDate from, LocalDate to,
			Pageable pageable) {

		Project project = requireProject(id);
		if (!project.getStatus().isOpen()) {
			throw new ConflictException("Project is " + project.getStatus() + "; people can only join open projects");
		}

		LocalDate start = from != null ? from : laterOf(LocalDate.now(), project.getStartDate());
		LocalDate end = to != null ? to
				: project.getEndDate() != null && !project.getEndDate().isBefore(start) ? project.getEndDate()
				: start.plusDays(DEFAULT_WINDOW_DAYS - 1);
		if (end.isBefore(start)) {
			throw new IllegalArgumentException("to cannot be before from");
		}
		int windowDays = WorkingDays.between(start, end);

		record Ranked(CandidateResponse candidate, double score) {
		}

		List<Ranked> ranked = projectQueries.candidates(id, start, end).stream()
				.map(row -> {
					int free = MAX_ALLOCATION - Allocations.peak(row.bookings(), start, end).percent();
					int leave = leaveDays(row.leave(), LeaveStatus.APPROVED, start, end);
					int pending = leaveDays(row.leave(), LeaveStatus.PENDING, start, end);
					int availability = windowDays == 0 ? 100
							: (int) Math.round(100.0 * (windowDays - leave) / windowDays);
					return new Ranked(new CandidateResponse(row.employee(), (int) Math.round(row.score() * 100),
							free, leave, pending, availability, row.inOwningDepartment(), row.skills()), row.score());
				})
				.filter(r -> r.candidate().freeCapacityPercent() >= allocationPercent
						&& r.candidate().availabilityPercent() > 0)
				.sorted(Comparator.comparingDouble(Ranked::score).reversed()
						.thenComparing(r -> !r.candidate().inOwningDepartment())
						.thenComparing(r -> -r.candidate().availabilityPercent())
						.thenComparing(r -> -r.candidate().freeCapacityPercent())
						.thenComparing(r -> r.candidate().employee().name())
						.thenComparing(r -> r.candidate().employee().id()))
				.toList();

		List<CandidateResponse> page = ranked.stream()
				.skip(pageable.getOffset())
				.limit(pageable.getPageSize())
				.map(Ranked::candidate)
				.toList();
		return new PageImpl<>(page, pageable, ranked.size());
	}

	private static int leaveDays(List<Period> leave, LeaveStatus status, LocalDate from, LocalDate to) {
		return leave.stream()
				.filter(period -> period.status() == status)
				.mapToInt(period -> WorkingDays.overlap(period.startDate(), period.endDate(), from, to))
				.sum();
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

		Optional<ProjectMemberResponse> previous = projectQueries.member(id, employeeId);
		DateRange dates = assignmentDates(project, previous, request.startDate(), request.endDate(),
				Boolean.TRUE.equals(request.openEnded()));

		int allocation = request.allocationPercent();
		requireCapacity(employee, id, allocation, dates);

		String role = trimToNull(request.role());

		projectQueries.assign(employeeId, id, role, allocation, dates.start(), dates.end());

		if (previous.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("employeeId", employeeId);
			details.put("role", role);
			details.put("allocationPercent", allocation);
			details.put("startDate", dates.start());
			details.put("endDate", dates.end());
			audit(AuditAction.PROJECT_MEMBER_ADDED, id, details);
		} else {
			Changes changes = new Changes()
					.track("role", previous.get().role(), role)
					.track("allocationPercent", previous.get().allocationPercent(), allocation)
					.track("startDate", previous.get().startDate(), dates.start())
					.track("endDate", previous.get().endDate(), dates.end());
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

	// ---- Allocation over time ---------------------------------------------------

	/** An assignment's dates, both inclusive; a null end means open-ended. */
	record DateRange(LocalDate start, LocalDate end) {
	}

	/**
	 * The dates an assignment gets. A requested date wins; otherwise someone already on the
	 * project keeps their current date, and someone new gets the project's dates, starting no
	 * earlier than today. openEnded removes the end date.
	 */
	DateRange assignmentDates(Project project, Optional<ProjectMemberResponse> current, LocalDate requestedStart,
			LocalDate requestedEnd, boolean openEnded) {

		if (openEnded && requestedEnd != null) {
			throw new IllegalArgumentException("Give an endDate or openEnded, not both");
		}

		LocalDate start = requestedStart != null ? requestedStart
				: current.map(ProjectMemberResponse::startDate)
						.orElseGet(() -> laterOf(LocalDate.now(), project.getStartDate()));
		LocalDate end = openEnded ? null
				: requestedEnd != null ? requestedEnd
				: current.isPresent() ? current.get().endDate() : project.getEndDate();

		if (end != null && end.isBefore(start)) {
			throw new IllegalArgumentException("The assignment would end (" + end + ") before it starts (" + start
					+ "); give a startDate and endDate");
		}
		return new DateRange(start, end);
	}

	/** On no day of the assignment may the employee's open projects add up to more than 100%. */
	void requireCapacity(Employee employee, String projectId, int allocation, DateRange dates) {
		Peak elsewhere = Allocations.peak(projectQueries.bookingsExcluding(employee.getId(), projectId),
				dates.start(), dates.end());
		if (elsewhere.percent() + allocation > MAX_ALLOCATION) {
			throw new ConflictException(employee.getName() + " is already " + elsewhere.percent()
					+ "% allocated on " + elsewhere.day() + "; adding " + allocation + "% would exceed "
					+ MAX_ALLOCATION + "%");
		}
	}

	private static LocalDate laterOf(LocalDate a, LocalDate b) {
		return b != null && b.isAfter(a) ? b : a;
	}

	// ---- Helpers --------------------------------------------------------------

	private void audit(AuditAction action, String projectId, Map<String, Object> details) {
		auditLog.record(action, AuditTargetType.PROJECT, projectId, details);
	}

	// Writes only what changed; a request that changes nothing is not audited.
	private void applyRequiredSkills(String projectId, List<SkillResponse> before, List<SkillResponse> after) {

		SkillChanges changes = SkillChanges.between(before, after);
		if (changes.isEmpty()) {
			return;
		}

		projectQueries.removeRequiredSkills(projectId, changes.removedIds());
		changes.toWrite().forEach(skill -> projectQueries.setRequiredSkill(projectId, skill.id(), skill.level()));

		audit(AuditAction.PROJECT_SKILLS_CHANGED, projectId, changes.details());
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
