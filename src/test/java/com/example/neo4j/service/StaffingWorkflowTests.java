package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.CreateStaffingRequest;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.StaffingQueries;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * The staffing approval workflow against an embedded Neo4j: a project lead requests,
 * HR approves or rejects. Each test runs in a transaction that is rolled back.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({StaffingService.class, ProjectService.class, EmployeeService.class, StaffingQueries.class,
        ProjectQueries.class, EmployeeQueries.class, AuditLog.class})
class StaffingWorkflowTests {

    private static final Neo4j neo4j = EmbeddedNeo4j.start();

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        EmbeddedNeo4j.register(registry, neo4j);
    }

    @AfterAll
    static void stopNeo4j() {
        neo4j.close();
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    StaffingService staffing;

    @Autowired
    ProjectService projects;

    @Autowired
    EmployeeService employees;

    @Autowired
    ProjectQueries projectQueries;

    @Autowired
    AppUserRepository users;

    @Autowired
    AuditLog auditLog;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    private String lead, dev, payroll;

    // Lena leads PAYROLL and has a login "lena"; Dev is a developer with no projects.
    private void setUp() {
        lead = hire("LENA");
        dev = hire("DEV");
        payroll = projects.create(new CreateProjectRequest("PAYROLL", "Payroll", null, ProjectStatus.ACTIVE,
                null, null, null, lead)).id();
        login("lena", lead);
    }

    private String hire(String code) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, null)).id();
    }

    private void login(String username, String employeeId) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("{noop}unused");
        user.setRoles(List.of("EMPLOYEE"));
        user.setEmployeeId(employeeId);
        users.save(user);
    }

    private static void actAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(username, null, "ROLE_HR"));
    }

    private StaffingRequestResponse requestAssign(String projectId, String employeeId, int allocation) {
        actAs("lena");
        return staffing.request(projectId, new CreateStaffingRequest(employeeId, StaffingAction.ASSIGN, "Backend dev",
                allocation), "lena");
    }

    private boolean onPayroll(String employeeId) {
        return projectQueries.member(payroll, employeeId).isPresent();
    }

    @Test
    void leadRequestsAndHrApproves() {
        setUp();

        StaffingRequestResponse pending = requestAssign(payroll, dev, 50);
        assertThat(pending.status()).isEqualTo(StaffingRequestStatus.PENDING);
        assertThat(pending.project().code()).isEqualTo("PAYROLL");
        assertThat(pending.employee().name()).isEqualTo("Name DEV");
        assertThat(pending.requestedBy()).isEqualTo("lena");
        assertThat(pending.requestedAt()).isNotNull();
        assertThat(onPayroll(dev)).isFalse();

        actAs("hr.user");
        StaffingRequestResponse approved = staffing.approve(pending.id(), "hr.user");

        assertThat(approved.status()).isEqualTo(StaffingRequestStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("hr.user");
        assertThat(approved.decidedAt()).isNotNull();
        assertThat(projectQueries.member(payroll, dev).orElseThrow().allocationPercent()).isEqualTo(50);

        // The project's history shows the whole workflow, newest first, with who did each step.
        List<AuditEventResponse> history = auditLog.search(AuditFilter.forTarget(AuditTargetType.PROJECT, payroll),
                FIRST_PAGE).getContent();
        assertThat(history.subList(0, 3)).extracting(AuditEventResponse::action).containsExactly(
                AuditAction.STAFFING_REQUEST_APPROVED, AuditAction.PROJECT_MEMBER_ADDED, AuditAction.STAFFING_REQUESTED);
        assertThat(history.subList(0, 3)).extracting(AuditEventResponse::actor)
                .containsExactly("hr.user", "hr.user", "lena");
        assertThat(history.get(2).details()).containsEntry("allocationPercent", 50).containsEntry("employeeId", dev);
    }

    @Test
    void rulesAreCheckedAgainWhenHrApproves() {
        setUp();
        String other = projects.create(new CreateProjectRequest("OTHER", "Other", null, ProjectStatus.ACTIVE,
                null, null, null, null)).id();

        StaffingRequestResponse pending = requestAssign(payroll, dev, 60);

        // Meanwhile HR books Dev at 50% elsewhere, so 60% more would exceed 100%.
        actAs("hr.user");
        projects.assign(other, dev, new AssignmentRequest("Dev", 50));

        assertThatThrownBy(() -> staffing.approve(pending.id(), "hr.user"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("100%");
        assertThat(staffing.findById(pending.id()).status()).isEqualTo(StaffingRequestStatus.PENDING);
        assertThat(onPayroll(dev)).isFalse();

        StaffingRequestResponse rejected = staffing.reject(pending.id(), "  Dev is fully booked  ", "hr.user");
        assertThat(rejected.status()).isEqualTo(StaffingRequestStatus.REJECTED);
        assertThat(rejected.reason()).isEqualTo("Dev is fully booked");

        assertThatThrownBy(() -> staffing.approve(pending.id(), "hr.user"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already REJECTED");
    }

    @Test
    void invalidRequestsAreRefusedUpFront() {
        setUp();
        String busy = hire("BUSY");
        actAs("hr.user");
        projects.assign(payroll, busy, new AssignmentRequest("Dev", 90));

        // Over 100%.
        assertThatThrownBy(() -> requestAssign(projects.create(new CreateProjectRequest("X", "X", null,
                ProjectStatus.ACTIVE, null, null, null, lead)).id(), busy, 20))
                .isInstanceOf(ConflictException.class);

        // ASSIGN without an allocation.
        assertThatThrownBy(() -> staffing.request(payroll,
                new CreateStaffingRequest(dev, StaffingAction.ASSIGN, null, null), "lena"))
                .isInstanceOf(IllegalArgumentException.class);

        // Removing someone who isn't on the project.
        assertThatThrownBy(() -> staffing.request(payroll,
                new CreateStaffingRequest(dev, StaffingAction.REMOVE, null, null), "lena"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not on this project");

        // Only one pending request per person per project.
        requestAssign(payroll, dev, 30);
        assertThatThrownBy(() -> requestAssign(payroll, dev, 40))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a pending request");

        // Finished projects can't be restaffed.
        actAs("hr.user");
        String done = projects.create(new CreateProjectRequest("DONE", "Done", null, ProjectStatus.COMPLETED,
                null, null, null, null)).id();
        assertThatThrownBy(() -> requestAssign(done, dev, 10)).isInstanceOf(ConflictException.class);
    }

    @Test
    void nobodyApprovesTheirOwnRequestAndOnlyTheRequesterCancels() {
        setUp();
        StaffingRequestResponse pending = requestAssign(payroll, dev, 50);

        // Even if the lead also had the HR role, they can't approve their own request.
        assertThatThrownBy(() -> staffing.approve(pending.id(), "lena"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("own request");

        assertThatThrownBy(() -> staffing.cancel(pending.id(), "someone.else"))
                .isInstanceOf(AccessDeniedException.class);

        StaffingRequestResponse cancelled = staffing.cancel(pending.id(), "lena");
        assertThat(cancelled.status()).isEqualTo(StaffingRequestStatus.CANCELLED);

        // A cancelled request frees the slot for a new one.
        assertThat(requestAssign(payroll, dev, 40).status()).isEqualTo(StaffingRequestStatus.PENDING);
    }

    @Test
    void removalRequestsTakeSomeoneOffWhenApproved() {
        setUp();
        actAs("hr.user");
        projects.assign(payroll, dev, new AssignmentRequest("Dev", 50));

        actAs("lena");
        StaffingRequestResponse pending = staffing.request(payroll,
                new CreateStaffingRequest(dev, StaffingAction.REMOVE, null, null), "lena");
        assertThat(pending.allocationPercent()).isNull();
        assertThat(onPayroll(dev)).isTrue();

        actAs("hr.user");
        staffing.approve(pending.id(), "hr.user");
        assertThat(onPayroll(dev)).isFalse();
    }

    @Test
    void onlyTheCurrentActiveLeadCounts() {
        setUp();
        String other = hire("OTHER");
        login("other", other);

        assertThat(projectQueries.isLedBy("lena", payroll)).isTrue();
        assertThat(projectQueries.isLedBy("other", payroll)).isFalse();
        assertThat(projectQueries.isLedBy("no.such.user", payroll)).isFalse();

        // Changing the lead moves the right immediately.
        projects.setLead(payroll, other);
        assertThat(projectQueries.isLedBy("lena", payroll)).isFalse();
        assertThat(projectQueries.isLedBy("other", payroll)).isTrue();

        // A disabled login loses it.
        AppUser user = users.findByUsername("other").orElseThrow();
        user.setEnabled(false);
        users.save(user);
        assertThat(projectQueries.isLedBy("other", payroll)).isFalse();
    }

    @Autowired
    com.example.neo4j.repository.StaffingRequestRepository requestRepository;

    @Test
    void aStaleCopyOfADecidedRequestCannotBeSaved() {
        setUp();
        StaffingRequestResponse pending = requestAssign(payroll, dev, 50);

        // Two HR users open the same request; the first one decides it.
        var staleCopy = requestRepository.findById(pending.id()).orElseThrow();
        actAs("hr.user");
        staffing.reject(pending.id(), "No budget", "hr.user");

        // The second one's decision, based on the old version, is refused.
        staleCopy.setStatus(StaffingRequestStatus.APPROVED);
        assertThatThrownBy(() -> requestRepository.save(staleCopy))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(staffing.findById(pending.id()).status()).isEqualTo(StaffingRequestStatus.REJECTED);
    }

    @Test
    void theQueueCanBeFilteredAndIsOldestFirst() {
        setUp();
        String second = hire("SECOND");
        StaffingRequestResponse first = requestAssign(payroll, dev, 10);
        StaffingRequestResponse next = requestAssign(payroll, second, 10);
        staffing.cancel(first.id(), "lena");

        assertThat(staffing.search(null, StaffingRequestStatus.PENDING, FIRST_PAGE).getContent())
                .extracting(StaffingRequestResponse::id).containsExactly(next.id());
        assertThat(staffing.search(payroll, null, FIRST_PAGE).getContent())
                .extracting(StaffingRequestResponse::id).containsExactly(first.id(), next.id());
        assertThat(staffing.search("other-project", null, FIRST_PAGE).getTotalElements()).isZero();
    }
}
