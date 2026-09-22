package com.example.neo4j.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.audit.Changes;
import com.example.neo4j.dto.CreateUserRequest;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.security.Role;

@Service
public class UserService {

	private final AppUserRepository userRepository;
	private final EmployeeRepository employeeRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuditLog auditLog;

	public UserService(AppUserRepository userRepository, EmployeeRepository employeeRepository,
			PasswordEncoder passwordEncoder, AuditLog auditLog) {
		this.userRepository = userRepository;
		this.employeeRepository = employeeRepository;
		this.passwordEncoder = passwordEncoder;
		this.auditLog = auditLog;
	}

	@Transactional
	public AppUser create(CreateUserRequest request) {

		if (userRepository.existsByUsername(request.username())) {
			throw new ConflictException("Username already exists: " + request.username());
		}

		AppUser user = new AppUser();
		user.setUsername(request.username());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setRoles(request.roles().stream().map(Role::name).sorted().toList());

		if (request.employeeId() != null) {
			user.setEmployeeId(requireLinkableEmployee(request.employeeId(), null));
		}

		AppUser saved = userRepository.save(user);

		// Never the password or its hash.
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("roles", saved.getRoles());
		details.put("employeeId", saved.getEmployeeId());
		auditLog.record(AuditAction.USER_CREATED, AuditTargetType.USER, saved.getUsername(), details);

		return saved;
	}

	@Transactional(readOnly = true)
	public Page<AppUser> findAll(Pageable pageable) {
		// Stable order so users don't repeat or go missing between pages.
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				Sort.by("username", "id"));
		return userRepository.findAll(sorted);
	}

	@Transactional
	public AppUser linkEmployee(String userId, String employeeId) {

		AppUser user = requireUser(userId);
		String previous = user.getEmployeeId();
		user.setEmployeeId(requireLinkableEmployee(employeeId, userId));

		return saveLinkChange(user, previous);
	}

	@Transactional
	public AppUser unlinkEmployee(String userId) {

		AppUser user = requireUser(userId);
		String previous = user.getEmployeeId();
		user.setEmployeeId(null);

		return saveLinkChange(user, previous);
	}

	@Transactional(readOnly = true)
	public Optional<String> findEmployeeId(String username) {
		return userRepository.findByUsername(username).map(AppUser::getEmployeeId);
	}

	private AppUser saveLinkChange(AppUser user, String previousEmployeeId) {

		if (Objects.equals(previousEmployeeId, user.getEmployeeId())) {
			return user;
		}

		AppUser saved = userRepository.save(user);
		auditLog.record(AuditAction.USER_EMPLOYEE_LINK_CHANGED, AuditTargetType.USER, saved.getUsername(),
				new Changes().track("employeeId", previousEmployeeId, saved.getEmployeeId()).asMap());
		return saved;
	}

	private AppUser requireUser(String userId) {
		return userRepository.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
	}

	// The employee must exist, still work here, and not already belong to another login.
	private String requireLinkableEmployee(String employeeId, String userId) {

		Employee employee = employeeRepository.findById(employeeId)
				.orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId));

		if (employee.getStatus() == EmploymentStatus.TERMINATED) {
			throw new ConflictException("Cannot link a login to a terminated employee");
		}

		userRepository.findByEmployeeId(employeeId)
				.filter(other -> !other.getId().equals(userId))
				.ifPresent(other -> {
					throw new ConflictException("Employee is already linked to user " + other.getUsername());
				});

		return employeeId;
	}
}
