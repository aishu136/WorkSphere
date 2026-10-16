package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateLeaveRequest;
import com.example.neo4j.dto.LeaveBalanceResponse;
import com.example.neo4j.dto.LeaveFilter;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.LeaveType;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.support.ServiceTest;
import com.example.neo4j.support.SharedNeo4jTest;

/**
 * The leave workflow against an embedded Neo4j: an employee requests, a manager above them or HR
 * decides.
 *
 * Dates are in 2030 so "hasn't started yet" doesn't depend on today. 4 March 2030 is a Monday.
 */
@ServiceTest
class LeaveWorkflowTests extends SharedNeo4jTest {

    @Autowired
    LeaveService leave;

    @Autowired
    EmployeeService employees;

    @Autowired
    AppUserRepository users;

    @Autowired
    AuditLog auditLog;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);
    private static final LeaveFilter ALL = new LeaveFilter(null, null, null, null);

    private static final LocalDate MON = LocalDate.of(2030, 3, 4);
    private static final LocalDate FRI = LocalDate.of(2030, 3, 8);

    // Cleo <- Mia <- Dev (Dev reports to Mia, Mia to Cleo). Sam is in another team.
    private String cleo, mia, dev, sam;

    @BeforeEach
    void setUp() {
        cleo = hire("CLEO");
        mia = hire("MIA");
        dev = hire("DEV");
        sam = hire("SAM");
        employees.assignManager(mia, cleo);
        employees.assignManager(dev, mia);
        login("cleo", cleo);
        login("mia", mia);
        login("dev", dev);
        login("sam", sam);
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
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

    private static void actAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(username, null, role));
    }

    private LeaveRequestResponse request(String username, LeaveType type, LocalDate start, LocalDate end) {
        actAs(username, "ROLE_EMPLOYEE");
        return leave.request(new CreateLeaveRequest(type, start, end, "Family visit"), username);
    }

    private LeaveRequestResponse approve(String requestId, String username, String role) {
        actAs(username, role);
        return leave.approve(requestId, username);
    }

    private List<AuditAction> history(String employeeId) {
        return auditLog.search(AuditFilter.forTarget(AuditTargetType.EMPLOYEE, employeeId), FIRST_PAGE).getContent()
                .stream().map(AuditEventResponse::action).filter(a -> a.name().startsWith("LEAVE_")).toList();
    }

    // ---- Requesting and deciding ------------------------------------------------

    @Test
    void anEmployeeRequestsAndTheirManagerApproves() {
        // Monday to Sunday is 5 working days.
        LeaveRequestResponse requested = request("dev", LeaveType.ANNUAL, MON, MON.plusDays(6));
        assertThat(requested.status()).isEqualTo(LeaveStatus.PENDING);
        assertThat(requested.workingDays()).isEqualTo(5);
        assertThat(requested.employee().id()).isEqualTo(dev);
        assertThat(requested.note()).isEqualTo("Family visit");

        // Not their own, and not someone outside the reporting line.
        actAs("dev", "ROLE_EMPLOYEE");
        assertThatThrownBy(() -> leave.approve(requested.id(), "dev")).isInstanceOf(ConflictException.class);
        actAs("sam", "ROLE_EMPLOYEE");
        assertThatThrownBy(() -> leave.approve(requested.id(), "sam")).isInstanceOf(AccessDeniedException.class);

        LeaveRequestResponse approved = approve(requested.id(), "mia", "ROLE_EMPLOYEE");
        assertThat(approved.status()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("mia");
        assertThat(approved.decidedAt()).isNotNull();

        assertThatThrownBy(() -> approve(requested.id(), "cleo", "ROLE_EMPLOYEE"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already APPROVED");

        assertThat(leave.balance(dev, 2030)).isEqualTo(new LeaveBalanceResponse(dev, 2030, 20, 5, 0, 15));
        assertThat(history(dev)).containsExactly(AuditAction.LEAVE_APPROVED, AuditAction.LEAVE_REQUESTED);
    }

    @Test
    void managersHigherUpAndHrCanDecideButNobodyDecidesTheirOwn() {
        String first = request("dev", LeaveType.SICK, MON, MON).id();
        assertThat(approve(first, "cleo", "ROLE_EMPLOYEE").status()).isEqualTo(LeaveStatus.APPROVED);

        String second = request("dev", LeaveType.OTHER, FRI, FRI).id();
        actAs("hr", "ROLE_HR");
        LeaveRequestResponse rejected = leave.reject(second, "  Release week  ", "hr");
        assertThat(rejected.status()).isEqualTo(LeaveStatus.REJECTED);
        assertThat(rejected.reason()).isEqualTo("Release week");

        // HR can't approve their own leave either; another HR user can.
        login("hana", hire("HANA"));
        String hanasOwn = request("hana", LeaveType.ANNUAL, MON, FRI).id();
        assertThatThrownBy(() -> approve(hanasOwn, "hana", "ROLE_HR")).isInstanceOf(ConflictException.class);
        assertThat(approve(hanasOwn, "hr", "ROLE_HR").status()).isEqualTo(LeaveStatus.APPROVED);
    }

    @Test
    void datesMustBeValidAndNotOverlap() {
        request("dev", LeaveType.ANNUAL, MON, FRI);

        assertThatThrownBy(() -> request("dev", LeaveType.SICK, FRI, FRI.plusDays(3)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already have");
        // A weekend has no working days.
        assertThatThrownBy(() -> request("dev", LeaveType.ANNUAL, FRI.plusDays(1), FRI.plusDays(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("dev", LeaveType.ANNUAL, FRI.plusDays(10), FRI.plusDays(9)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("dev", LeaveType.UNPAID, MON.plusMonths(1), MON.plusMonths(14)))
                .isInstanceOf(IllegalArgumentException.class);

        // Someone else's leave on the same days is fine.
        assertThat(request("sam", LeaveType.ANNUAL, MON, FRI).status()).isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void annualLeaveMustFitTheAllowanceAndPendingDaysCount() {
        // 5 + 15 = 20 working days, the whole allowance. 1 April and 19 April 2030 are a Monday and a Friday.
        request("dev", LeaveType.ANNUAL, MON, FRI);
        request("dev", LeaveType.ANNUAL, LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 19));
        assertThat(leave.balance(dev, 2030)).isEqualTo(new LeaveBalanceResponse(dev, 2030, 20, 0, 20, 0));

        assertThatThrownBy(() -> request("dev", LeaveType.ANNUAL, LocalDate.of(2030, 5, 6), LocalDate.of(2030, 5, 6)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Not enough annual leave in 2030: 0 of 20 days left, 1 requested");

        // Sick leave doesn't use the allowance.
        assertThat(request("dev", LeaveType.SICK, LocalDate.of(2030, 5, 6), LocalDate.of(2030, 5, 6)).status())
                .isEqualTo(LeaveStatus.PENDING);
    }

    @Test
    void leaveOverNewYearCountsAgainstEachYear() {
        // Monday 23 December 2030 to Friday 3 January 2031: 7 working days in 2030, 3 in 2031.
        LeaveRequestResponse holiday = request("dev", LeaveType.ANNUAL, LocalDate.of(2030, 12, 23),
                LocalDate.of(2031, 1, 3));
        assertThat(holiday.workingDays()).isEqualTo(10);

        assertThat(leave.balance(dev, 2030).pendingDays()).isEqualTo(7);
        assertThat(leave.balance(dev, 2031).pendingDays()).isEqualTo(3);
        assertThat(leave.balance(dev, 2032).remainingDays()).isEqualTo(20);
    }

    // ---- Cancelling ---------------------------------------------------------------

    @Test
    void onlyTheRequesterCancelsAndOnlyBeforeLeaveStarts() {
        String pending = request("dev", LeaveType.ANNUAL, MON, FRI).id();
        actAs("mia", "ROLE_EMPLOYEE");
        assertThatThrownBy(() -> leave.cancel(pending, "mia")).isInstanceOf(AccessDeniedException.class);
        actAs("dev", "ROLE_EMPLOYEE");
        assertThat(leave.cancel(pending, "dev").status()).isEqualTo(LeaveStatus.CANCELLED);
        assertThatThrownBy(() -> leave.cancel(pending, "dev")).isInstanceOf(ConflictException.class);

        // Cancelled days are free again.
        String future = request("dev", LeaveType.ANNUAL, MON, FRI).id();
        approve(future, "mia", "ROLE_EMPLOYEE");
        actAs("dev", "ROLE_EMPLOYEE");
        assertThat(leave.cancel(future, "dev").status()).isEqualTo(LeaveStatus.CANCELLED);

        // Leave already taken stays.
        String past = request("dev", LeaveType.SICK, LocalDate.of(2025, 3, 3), LocalDate.of(2025, 3, 4)).id();
        approve(past, "mia", "ROLE_EMPLOYEE");
        actAs("dev", "ROLE_EMPLOYEE");
        assertThatThrownBy(() -> leave.cancel(past, "dev"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("before it starts");
    }

    @Test
    void terminationCancelsOpenLeaveAndKeepsLeaveTaken() {
        String pending = request("dev", LeaveType.ANNUAL, MON, FRI).id();
        String future = request("dev", LeaveType.ANNUAL, MON.plusWeeks(2), FRI.plusWeeks(2)).id();
        approve(future, "mia", "ROLE_EMPLOYEE");
        String taken = request("dev", LeaveType.SICK, LocalDate.of(2025, 3, 3), LocalDate.of(2025, 3, 3)).id();
        approve(taken, "mia", "ROLE_EMPLOYEE");

        actAs("hr", "ROLE_HR");
        employees.terminate(dev);

        assertThat(leave.findById(pending, "hr").status()).isEqualTo(LeaveStatus.CANCELLED);
        LeaveRequestResponse cancelled = leave.findById(future, "hr");
        assertThat(cancelled.status()).isEqualTo(LeaveStatus.CANCELLED);
        assertThat(cancelled.reason()).isEqualTo("Employee terminated");
        assertThat(cancelled.decidedBy()).isEqualTo("hr");
        assertThat(leave.findById(taken, "hr").status()).isEqualTo(LeaveStatus.APPROVED);

        AuditEventResponse termination = auditLog.search(new AuditFilter(null, AuditAction.EMPLOYEE_TERMINATED,
                AuditTargetType.EMPLOYEE, dev, null, null), FIRST_PAGE).getContent().get(0);
        assertThat(termination.details().get("cancelledLeaveRequestIds"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder(pending, future);
    }

    // ---- Who sees what --------------------------------------------------------------

    @Test
    void employeesSeeTheirOwnLeaveAndManagersTheirTeams() {
        String devLeave = request("dev", LeaveType.ANNUAL, MON, FRI).id();
        String samLeave = request("sam", LeaveType.SICK, MON, MON).id();
        String miaLeave = request("mia", LeaveType.ANNUAL, MON.plusWeeks(1), FRI.plusWeeks(1)).id();

        actAs("dev", "ROLE_EMPLOYEE");
        assertThat(ids(leave.mine("dev", ALL, FIRST_PAGE).getContent())).containsExactly(devLeave);
        assertThat(leave.findById(devLeave, "dev").id()).isEqualTo(devLeave);
        assertThatThrownBy(() -> leave.findById(samLeave, "dev")).isInstanceOf(AccessDeniedException.class);

        // Team: everyone below, at any depth, not the manager themselves.
        assertThat(ids(leave.team("mia", ALL, FIRST_PAGE).getContent())).containsExactly(devLeave);
        assertThat(ids(leave.team("cleo", ALL, FIRST_PAGE).getContent())).containsExactly(devLeave, miaLeave);
        assertThat(leave.findById(devLeave, "cleo").id()).isEqualTo(devLeave);

        // Who is away in a given week, and the queue by status.
        assertThat(ids(leave.team("cleo", new LeaveFilter(null, null, MON.plusWeeks(1), FRI.plusWeeks(1)),
                FIRST_PAGE).getContent())).containsExactly(miaLeave);
        approve(devLeave, "mia", "ROLE_EMPLOYEE");
        assertThat(ids(leave.team("cleo", new LeaveFilter(null, LeaveStatus.PENDING, null, null), FIRST_PAGE)
                .getContent())).containsExactly(miaLeave);

        actAs("hr", "ROLE_HR");
        assertThat(leave.search(ALL, FIRST_PAGE).getTotalElements()).isEqualTo(3);
        assertThat(leave.findById(samLeave, "hr").id()).isEqualTo(samLeave);
    }

    @Test
    void aLoginWithoutAnEmployeeCannotRequestLeave() {
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash("{noop}unused");
        admin.setRoles(List.of("ADMIN"));
        users.save(admin);

        assertThatThrownBy(() -> request("admin", LeaveType.ANNUAL, MON, FRI))
                .isInstanceOf(ConflictException.class).hasMessageContaining("not linked");
    }

    private static List<String> ids(List<LeaveRequestResponse> requests) {
        return requests.stream().map(LeaveRequestResponse::id).toList();
    }
}
