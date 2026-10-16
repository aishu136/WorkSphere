package com.example.neo4j.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.HistoryPeriod;
import com.example.neo4j.dto.ReportingHistoryResponse;
import com.example.neo4j.entity.Department;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.DepartmentRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;

/*
 * The org chart over time, rebuilt from the audit trail.
 *
 * Every change of manager or department is audited with its before and after value, and
 * termination records the links it removed. Reading an employee's events oldest first gives the
 * stretches of time they had each manager and department. Anything before the first recorded
 * event (for example, data from before the audit trail existed) shows as starting at an unknown
 * time (from = null). Changes made directly in the database, bypassing the API, are not seen.
 */
@Service
public class OrgHistoryService {

	// Events read per employee; far more than one person's reporting changes.
	static final int MAX_EVENTS = 1000;

	private static final String MANAGER = "managerId";
	private static final String DEPARTMENT = "departmentId";

	private final AuditLog auditLog;
	private final EmployeeQueries employeeQueries;
	private final EmployeeRepository employeeRepository;
	private final DepartmentRepository departmentRepository;

	public OrgHistoryService(AuditLog auditLog, EmployeeQueries employeeQueries,
			EmployeeRepository employeeRepository, DepartmentRepository departmentRepository) {
		this.auditLog = auditLog;
		this.employeeQueries = employeeQueries;
		this.employeeRepository = employeeRepository;
		this.departmentRepository = departmentRepository;
	}

	@Transactional(readOnly = true)
	public ReportingHistoryResponse history(String employeeId) {

		EmployeeResponse now = employeeQueries.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));
		List<AuditEventResponse> events = eventsOldestFirst(employeeId);

		return new ReportingHistoryResponse(employeeId,
				named(periods(events, MANAGER, "previousManagerId", AuditAction.EMPLOYEE_MANAGER_CHANGED,
						now.manager() == null ? null : now.manager().id()), this::employeeName),
				named(periods(events, DEPARTMENT, "previousDepartmentId", AuditAction.EMPLOYEE_DEPARTMENT_CHANGED,
						now.department() == null ? null : now.department().id()), this::departmentName));
	}

	/** Who reported directly to this manager at the end of the given day, by name. */
	@Transactional(readOnly = true)
	public List<EmployeeSummary> reportsOn(String managerId, LocalDate date) {

		if (!employeeRepository.existsById(managerId)) {
			throw new ResourceNotFoundException("Employee not found with id: " + managerId);
		}
		OffsetDateTime endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();

		// Anyone who reports to them now, or whose history mentions them as a manager.
		Set<String> candidates = new LinkedHashSet<>();
		employeeQueries.directReports(managerId, PageRequest.of(0, MAX_EVENTS)).forEach(e -> candidates.add(e.id()));
		candidates.addAll(auditLog.employeesMentioning(
				List.of(AuditAction.EMPLOYEE_MANAGER_CHANGED, AuditAction.EMPLOYEE_TERMINATED), managerId));

		return candidates.stream()
				.filter(id -> history(id).managers().stream()
						.anyMatch(period -> managerId.equals(period.id()) && covers(period, endOfDay)))
				.map(id -> employeeRepository.findById(id).orElse(null))
				.filter(Objects::nonNull)
				.map(e -> new EmployeeSummary(e.getId(), e.getName(), e.getJobTitle()))
				.sorted(Comparator.comparing(EmployeeSummary::name).thenComparing(EmployeeSummary::id))
				.toList();
	}

	// ---- Rebuilding periods ---------------------------------------------------

	private record Change(OffsetDateTime at, String from, String to) {
	}

	private record Period(String id, OffsetDateTime from, OffsetDateTime to) {
	}

	/**
	 * The value before the first change is that change's "from" (or the current value if nothing
	 * changed); each change then starts a new period. Periods without a value (no manager, no
	 * department) are left out. Employment starts at EMPLOYEE_CREATED when it is recorded.
	 */
	private static List<Period> periods(List<AuditEventResponse> events, String field, String terminationField,
			AuditAction changeAction, String current) {

		OffsetDateTime start = events.stream()
				.filter(e -> e.action() == AuditAction.EMPLOYEE_CREATED)
				.map(AuditEventResponse::timestamp)
				.findFirst().orElse(null);

		List<Change> changes = new ArrayList<>();
		for (AuditEventResponse event : events) {
			if (event.action() == changeAction && event.details().get(field) instanceof Map<?, ?> change) {
				changes.add(new Change(event.timestamp(), string(change.get("from")), string(change.get("to"))));
			} else if (event.action() == AuditAction.EMPLOYEE_TERMINATED
					&& event.details().get(terminationField) != null) {
				changes.add(new Change(event.timestamp(), string(event.details().get(terminationField)), null));
			}
		}

		List<Period> periods = new ArrayList<>();
		String value = changes.isEmpty() ? current : changes.get(0).from();
		OffsetDateTime since = start;
		for (Change change : changes) {
			// Kept even when it starts and ends in the same millisecond: timestamps are stored to the
			// millisecond, so two quick changes can share one.
			if (value != null) {
				periods.add(new Period(value, since, change.at()));
			}
			value = change.to();
			since = change.at();
		}
		if (value != null) {
			periods.add(new Period(value, since, null));
		}
		return periods;
	}

	private static List<HistoryPeriod> named(List<Period> periods, Function<String, String> names) {
		return periods.stream().map(p -> new HistoryPeriod(p.id(), names.apply(p.id()), p.from(), p.to())).toList();
	}

	private static boolean covers(HistoryPeriod period, OffsetDateTime moment) {
		return (period.from() == null || period.from().isBefore(moment))
				&& (period.to() == null || !period.to().isBefore(moment));
	}

	private List<AuditEventResponse> eventsOldestFirst(String employeeId) {
		List<AuditEventResponse> newestFirst = auditLog.search(
				AuditFilter.forTarget(AuditTargetType.EMPLOYEE, employeeId), PageRequest.of(0, MAX_EVENTS)).getContent();
		return newestFirst.reversed();
	}

	private String employeeName(String id) {
		return employeeRepository.findById(id).map(Employee::getName).orElse(null);
	}

	private String departmentName(String id) {
		return departmentRepository.findById(id).map(Department::getName).orElse(null);
	}

	private static String string(Object value) {
		return value == null ? null : value.toString();
	}
}
