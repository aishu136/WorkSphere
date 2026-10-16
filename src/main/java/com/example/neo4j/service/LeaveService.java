package com.example.neo4j.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.CreateLeaveRequest;
import com.example.neo4j.dto.LeaveBalanceResponse;
import com.example.neo4j.dto.LeaveFilter;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.LeaveRequest;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.LeaveType;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.notification.LeaveRequestEvent;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.LeaveQueries;
import com.example.neo4j.repository.LeaveRequestRepository;
import com.example.neo4j.security.CurrentUser;

/*
 * Leave workflow: an employee requests time off for themselves, and a manager above them in the
 * org chart (at any depth) or HR approves or rejects it.
 *
 * - Leave is counted in working days (Monday to Friday).
 * - Nobody can have two pending or approved requests on the same day.
 * - ANNUAL leave must fit the yearly allowance. Pending requests count too when asking, so
 *   people can't queue more than they have; approval checks again against approved leave.
 * - Nobody can decide their own leave, even if they are HR.
 * - Every step is recorded in the employee's audit history and published as a LeaveRequestEvent,
 *   which triggers email notifications once the transaction commits.
 *
 * Only the leave queue across the whole company is restricted in SecurityConfig. Everything else
 * depends on the request (whose leave, who manages them), so it is checked here.
 */
@Service
public class LeaveService {

	private static final List<LeaveStatus> OPEN = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);

	private final LeaveRequestRepository requestRepository;
	private final LeaveQueries leaveQueries;
	private final EmployeeRepository employeeRepository;
	private final EmployeeQueries employeeQueries;
	private final AppUserRepository userRepository;
	private final AuditLog auditLog;
	private final ApplicationEventPublisher events;
	private final int annualAllowanceDays;

	public LeaveService(LeaveRequestRepository requestRepository, LeaveQueries leaveQueries,
			EmployeeRepository employeeRepository, EmployeeQueries employeeQueries, AppUserRepository userRepository,
			AuditLog auditLog, ApplicationEventPublisher events,
			@Value("${app.leave.annual-allowance-days:20}") int annualAllowanceDays) {
		this.requestRepository = requestRepository;
		this.leaveQueries = leaveQueries;
		this.employeeRepository = employeeRepository;
		this.employeeQueries = employeeQueries;
		this.userRepository = userRepository;
		this.auditLog = auditLog;
		this.events = events;
		this.annualAllowanceDays = annualAllowanceDays;
	}

	// ---- Reads ----------------------------------------------------------------

	/** Visible to the employee, any manager above them, and HR. */
	@Transactional(readOnly = true)
	public LeaveRequestResponse findById(String requestId, String username) {

		LeaveRequestResponse request = leaveQueries.findById(requestId)
				.orElseThrow(() -> notFound(requestId));

		String employeeId = request.employee() == null ? null : request.employee().id();
		boolean allowed = CurrentUser.isHrOrAdmin()
				|| username.equals(request.requestedBy())
				|| (employeeId != null && employeeQueries.isInTeamOf(username, employeeId, true));
		if (!allowed) {
			throw new AccessDeniedException("You can only see your own leave and your team's");
		}
		return request;
	}

	@Transactional(readOnly = true)
	public Page<LeaveRequestResponse> mine(String username, LeaveFilter filter, Pageable pageable) {
		return leaveQueries.search(filter.forEmployee(linkedEmployeeId(username)), null, pageable);
	}

	/** Leave of everyone below the caller in the org chart: their approval queue and team calendar. */
	@Transactional(readOnly = true)
	public Page<LeaveRequestResponse> team(String username, LeaveFilter filter, Pageable pageable) {
		return leaveQueries.search(filter, linkedEmployeeId(username), pageable);
	}

	/** Everyone's leave. HR or ADMIN (enforced in SecurityConfig). */
	@Transactional(readOnly = true)
	public Page<LeaveRequestResponse> search(LeaveFilter filter, Pageable pageable) {
		return leaveQueries.search(filter, null, pageable);
	}

	/** The employee linked to this login, if any. */
	@Transactional(readOnly = true)
	public Optional<String> employeeIdOf(String username) {
		return linkedEmployee(username);
	}

	/** True if the login may see this employee's leave: they are the employee or a manager above them. HR always may. */
	@Transactional(readOnly = true)
	public boolean canSeeLeaveOf(String username, String employeeId) {
		return employeeQueries.isInTeamOf(username, employeeId, true);
	}

	@Transactional(readOnly = true)
	public LeaveBalanceResponse balance(String employeeId, int year) {

		if (!employeeRepository.existsById(employeeId)) {
			throw new ResourceNotFoundException("Employee not found with id: " + employeeId);
		}

		int approved = annualDaysUsed(employeeId, year, List.of(LeaveStatus.APPROVED));
		int pending = annualDaysUsed(employeeId, year, List.of(LeaveStatus.PENDING));
		return new LeaveBalanceResponse(employeeId, year, annualAllowanceDays, approved, pending,
				annualAllowanceDays - approved - pending);
	}

	// ---- Employee -------------------------------------------------------------

	@Transactional
	public LeaveRequestResponse request(CreateLeaveRequest request, String username) {

		String employeeId = linkedEmployeeId(username);
		Employee employee = requireEmployee(employeeId);
		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException(employee.getName() + " is terminated");
		}

		LocalDate start = request.startDate();
		LocalDate end = request.endDate();
		if (end.isBefore(start)) {
			throw new IllegalArgumentException("End date cannot be before start date");
		}
		if (ChronoUnit.DAYS.between(start, end) >= 366) {
			throw new IllegalArgumentException("Leave can be at most a year long; request it in parts");
		}
		int workingDays = WorkingDays.between(start, end);
		if (workingDays == 0) {
			throw new IllegalArgumentException("These dates have no working days");
		}

		leaveQueries.lockEmployee(employeeId);
		if (leaveQueries.overlapsOpenLeave(employeeId, start, end, null)) {
			throw new ConflictException("You already have pending or approved leave on some of these days");
		}
		if (request.type().countsAgainstAllowance()) {
			requireAllowance(employeeId, start, end, OPEN);
		}

		LeaveRequest leave = new LeaveRequest();
		leave.setEmployeeId(employeeId);
		leave.setType(request.type());
		leave.setStartDate(start);
		leave.setEndDate(end);
		leave.setWorkingDays(workingDays);
		leave.setNote(trimToNull(request.note()));
		leave.setStatus(LeaveStatus.PENDING);
		leave.setRequestedBy(username);
		leave.setRequestedAt(Instant.now());

		LeaveRequest saved = requestRepository.save(leave);
		audit(AuditAction.LEAVE_REQUESTED, saved);
		publish(saved, LeaveRequestEvent.Type.REQUESTED);

		return leaveQueries.findById(saved.getId()).orElseThrow();
	}

	/** The requester can withdraw a pending request, or approved leave that hasn't started yet. */
	@Transactional
	public LeaveRequestResponse cancel(String requestId, String username) {

		LeaveRequest leave = requireRequest(requestId);
		if (!leave.getRequestedBy().equals(username)) {
			throw new AccessDeniedException("Only the person who requested this leave can cancel it");
		}

		boolean cancellable = leave.getStatus() == LeaveStatus.PENDING
				|| (leave.getStatus() == LeaveStatus.APPROVED && leave.getStartDate().isAfter(LocalDate.now()));
		if (!cancellable) {
			throw new ConflictException(leave.getStatus() == LeaveStatus.APPROVED
					? "Approved leave can only be cancelled before it starts"
					: "Leave request is already " + leave.getStatus());
		}

		decide(leave, LeaveStatus.CANCELLED, username, null);
		audit(AuditAction.LEAVE_CANCELLED, leave);
		publish(leave, LeaveRequestEvent.Type.CANCELLED);

		return leaveQueries.findById(requestId).orElseThrow();
	}

	// ---- Manager or HR --------------------------------------------------------

	/** Checks the overlap and annual allowance again, against what has been approved since. */
	@Transactional
	public LeaveRequestResponse approve(String requestId, String username) {

		LeaveRequest leave = requirePending(requestId);
		requireDecider(leave, username);

		Employee employee = requireEmployee(leave.getEmployeeId());
		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException(employee.getName() + " is terminated");
		}

		leaveQueries.lockEmployee(leave.getEmployeeId());
		if (leave.getType().countsAgainstAllowance()) {
			requireAllowance(leave.getEmployeeId(), leave.getStartDate(), leave.getEndDate(),
					List.of(LeaveStatus.APPROVED));
		}

		decide(leave, LeaveStatus.APPROVED, username, null);
		audit(AuditAction.LEAVE_APPROVED, leave);
		publish(leave, LeaveRequestEvent.Type.APPROVED);

		return leaveQueries.findById(requestId).orElseThrow();
	}

	@Transactional
	public LeaveRequestResponse reject(String requestId, String reason, String username) {

		LeaveRequest leave = requirePending(requestId);
		requireDecider(leave, username);

		decide(leave, LeaveStatus.REJECTED, username, reason.trim());
		audit(AuditAction.LEAVE_REJECTED, leave);
		publish(leave, LeaveRequestEvent.Type.REJECTED);

		return leaveQueries.findById(requestId).orElseThrow();
	}

	// ---- Helpers --------------------------------------------------------------

	// HR, or a manager above the employee; never the employee themselves.
	private void requireDecider(LeaveRequest leave, String username) {

		boolean own = leave.getRequestedBy().equals(username)
				|| leave.getEmployeeId().equals(linkedEmployee(username).orElse(null));
		if (own) {
			throw new ConflictException("You can't decide your own leave; your manager or HR must decide it");
		}
		if (!CurrentUser.isHrOrAdmin() && !employeeQueries.isInTeamOf(username, leave.getEmployeeId(), false)) {
			throw new AccessDeniedException("Only a manager above this employee, or HR, can decide their leave");
		}
	}

	private void requireAllowance(String employeeId, LocalDate start, LocalDate end, List<LeaveStatus> counted) {
		for (int year = start.getYear(); year <= end.getYear(); year++) {
			int requested = WorkingDays.inYear(year, start, end);
			int used = annualDaysUsed(employeeId, year, counted);
			if (used + requested > annualAllowanceDays) {
				throw new ConflictException("Not enough annual leave in " + year + ": "
						+ Math.max(0, annualAllowanceDays - used) + " of " + annualAllowanceDays + " days left, "
						+ requested + " requested");
			}
		}
	}

	// Working days of ANNUAL leave with these statuses that fall in the year.
	private int annualDaysUsed(String employeeId, int year, List<LeaveStatus> statuses) {
		return leaveQueries.periods(employeeId, LeaveType.ANNUAL, statuses,
						LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
				.mapToInt(period -> WorkingDays.inYear(year, period.startDate(), period.endDate()))
				.sum();
	}

	private Optional<String> linkedEmployee(String username) {
		return userRepository.findByUsername(username).map(AppUser::getEmployeeId);
	}

	private String linkedEmployeeId(String username) {
		return linkedEmployee(username)
				.orElseThrow(() -> new ConflictException("Your login is not linked to an employee"));
	}

	private Employee requireEmployee(String employeeId) {
		return employeeRepository.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
	}

	private LeaveRequest requireRequest(String requestId) {
		return requestRepository.findById(requestId).orElseThrow(() -> notFound(requestId));
	}

	private LeaveRequest requirePending(String requestId) {
		LeaveRequest leave = requireRequest(requestId);
		if (leave.getStatus() != LeaveStatus.PENDING) {
			throw new ConflictException("Leave request is already " + leave.getStatus());
		}
		return leave;
	}

	// Saving checks the version, so a concurrent decision on the same request fails instead of both applying.
	private void decide(LeaveRequest leave, LeaveStatus status, String username, String reason) {
		leave.setStatus(status);
		leave.setDecidedBy(username);
		leave.setDecidedAt(Instant.now());
		leave.setReason(reason);
		requestRepository.save(leave);
	}

	// Listeners run only if the transaction commits (see LeaveNotifier).
	private void publish(LeaveRequest leave, LeaveRequestEvent.Type type) {
		events.publishEvent(new LeaveRequestEvent(leave.getId(), type));
	}

	// Recorded on the employee, so their history shows their leave.
	private void audit(AuditAction action, LeaveRequest leave) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("requestId", leave.getId());
		details.put("type", leave.getType());
		details.put("startDate", leave.getStartDate());
		details.put("endDate", leave.getEndDate());
		details.put("workingDays", leave.getWorkingDays());
		details.put("requestedBy", leave.getRequestedBy());
		if (leave.getReason() != null) {
			details.put("reason", leave.getReason());
		}
		auditLog.record(action, AuditTargetType.EMPLOYEE, leave.getEmployeeId(), details);
	}

	private static ResourceNotFoundException notFound(String requestId) {
		return new ResourceNotFoundException("Leave request not found with id: " + requestId);
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
