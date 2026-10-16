package com.example.neo4j.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.CreateHolidayRequest;
import com.example.neo4j.dto.CreateOfficeRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.HolidayResponse;
import com.example.neo4j.dto.OfficeResponse;
import com.example.neo4j.dto.UpdateOfficeRequest;
import com.example.neo4j.entity.Office;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.OfficeQueries;
import com.example.neo4j.repository.OfficeRepository;

@Service
public class OfficeService {

	private final OfficeRepository officeRepository;
	private final OfficeQueries officeQueries;
	private final EmployeeQueries employeeQueries;
	private final AuditLog auditLog;

	public OfficeService(OfficeRepository officeRepository, OfficeQueries officeQueries,
			EmployeeQueries employeeQueries, AuditLog auditLog) {
		this.officeRepository = officeRepository;
		this.officeQueries = officeQueries;
		this.employeeQueries = employeeQueries;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	public OfficeResponse findById(String id) {
		return officeQueries.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Office not found with id: " + id));
	}

	@Transactional(readOnly = true)
	public Page<OfficeResponse> search(String text, Pageable pageable) {
		return officeQueries.search(text, pageable);
	}

	@Transactional(readOnly = true)
	public Page<EmployeeResponse> employees(String id, Pageable pageable) {
		requireExists(id);
		return employeeQueries.search(EmployeeFilter.byOffice(id), pageable);
	}

	@Transactional
	public OfficeResponse create(CreateOfficeRequest request) {

		String code = request.code().trim().toUpperCase(Locale.ROOT);
		if (officeRepository.existsByCode(code)) {
			throw new ConflictException("Office code already exists: " + code);
		}

		Office office = new Office();
		office.setCode(code);
		office.setName(request.name().trim());
		office.setCity(request.city().trim());
		office.setCountry(request.country().trim());
		office.setAddress(trimToNull(request.address()));
		office.setCapacity(request.capacity());

		String id = officeRepository.save(office).getId();

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", code);
		details.put("name", office.getName());
		details.put("city", office.getCity());
		details.put("country", office.getCountry());
		details.put("capacity", office.getCapacity());
		audit(AuditAction.OFFICE_CREATED, id, details);

		return findById(id);
	}

	@Transactional
	public OfficeResponse update(String id, UpdateOfficeRequest request) {

		Office office = officeRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Office not found with id: " + id));

		String name = request.name().trim();
		String city = request.city().trim();
		String country = request.country().trim();
		String address = trimToNull(request.address());

		Changes changes = new Changes()
				.track("name", office.getName(), name)
				.track("city", office.getCity(), city)
				.track("country", office.getCountry(), country)
				.track("address", office.getAddress(), address)
				.track("capacity", office.getCapacity(), request.capacity());

		if (!changes.isEmpty()) {
			office.setName(name);
			office.setCity(city);
			office.setCountry(country);
			office.setAddress(address);
			office.setCapacity(request.capacity());
			officeRepository.save(office);

			audit(AuditAction.OFFICE_UPDATED, id, changes.asMap());
		}

		return findById(id);
	}

	// Only empty offices can be deleted, so nobody is left pointing at a missing office.
	@Transactional
	public void delete(String id) {

		OfficeResponse office = findById(id);

		if (officeQueries.hasEmployees(id)) {
			throw new ConflictException("Move this office's " + office.employeeCount()
					+ " employee(s) to another office before deleting it");
		}

		officeQueries.removeAllHolidays(id);
		officeRepository.deleteById(id);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", office.code());
		details.put("name", office.name());
		details.put("city", office.city());
		audit(AuditAction.OFFICE_DELETED, id, details);
	}

	// ---- Holidays ---------------------------------------------------------------

	/** The office's public holidays in a calendar year, by date. */
	@Transactional(readOnly = true)
	public List<HolidayResponse> holidays(String id, int year) {
		requireExists(id);
		return officeQueries.holidays(id, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
	}

	/** Adds a public holiday. Leave and availability for people in this office skip it. One per day. */
	@Transactional
	public HolidayResponse addHoliday(String id, CreateHolidayRequest request) {
		requireExists(id);
		if (officeQueries.hasHolidayOn(id, request.date())) {
			throw new ConflictException("This office already has a holiday on " + request.date());
		}

		HolidayResponse holiday = officeQueries.addHoliday(id, request.date(), request.name().trim());
		audit(AuditAction.OFFICE_HOLIDAY_ADDED, id, holidayDetails(holiday));
		return holiday;
	}

	@Transactional
	public void removeHoliday(String id, String holidayId) {
		requireExists(id);
		HolidayResponse removed = officeQueries.removeHoliday(id, holidayId)
				.orElseThrow(() -> new ResourceNotFoundException("Holiday not found with id: " + holidayId));
		audit(AuditAction.OFFICE_HOLIDAY_REMOVED, id, holidayDetails(removed));
	}

	private static Map<String, Object> holidayDetails(HolidayResponse holiday) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("holidayId", holiday.id());
		details.put("date", holiday.date());
		details.put("name", holiday.name());
		return details;
	}

	private void audit(AuditAction action, String officeId, Map<String, Object> details) {
		auditLog.record(action, AuditTargetType.OFFICE, officeId, details);
	}

	private void requireExists(String id) {
		if (!officeRepository.existsById(id)) {
			throw new ResourceNotFoundException("Office not found with id: " + id);
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
