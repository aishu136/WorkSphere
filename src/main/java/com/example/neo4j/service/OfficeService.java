package com.example.neo4j.service;

import java.util.LinkedHashMap;
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
import com.example.neo4j.dto.CreateOfficeRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
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
		return employeeQueries.search(new EmployeeFilter(null, null, null, null, null, id, null), pageable);
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

		officeRepository.deleteById(id);

		Map<String, Object> details = new LinkedHashMap<>();
		details.put("code", office.code());
		details.put("name", office.name());
		details.put("city", office.city());
		audit(AuditAction.OFFICE_DELETED, id, details);
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
