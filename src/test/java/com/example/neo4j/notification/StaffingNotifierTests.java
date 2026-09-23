package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.StaffingQueries;

/** Recipients and content of the staffing emails put in the outbox. No database or mail server. */
class StaffingNotifierTests {

    /** One captured outbox entry. */
    record Queued(String to, String subject, String body, String reference) {
    }

    private StaffingQueries staffingQueries;
    private AppUserRepository users;
    private EmployeeRepository employees;
    private OutboxService outbox;

    @BeforeEach
    void setUp() {
        staffingQueries = mock(StaffingQueries.class);
        users = mock(AppUserRepository.class);
        employees = mock(EmployeeRepository.class);
        outbox = mock(OutboxService.class);

        // Lena (login "lena") leads the project; Dev is the person being staffed.
        AppUser lena = new AppUser();
        lena.setUsername("lena");
        lena.setEmployeeId("e-lena");
        when(users.findByUsername("lena")).thenReturn(Optional.of(lena));
        when(employees.findById("e-lena")).thenReturn(Optional.of(employee("e-lena", "Lena Lead", "lena@example.com")));
        when(employees.findById("e-dev")).thenReturn(Optional.of(employee("e-dev", "Dev Kumar", "dev@example.com")));
    }

    private static Employee employee(String id, String name, String email) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail(email);
        return employee;
    }

    private StaffingNotifier notifier(String hrEmail) {
        return new StaffingNotifier(outbox, staffingQueries, users, employees, hrEmail,
                "https://worksphere.example.com/");
    }

    private void givenRequest(StaffingAction action, StaffingRequestStatus status, String decidedBy, String reason,
                              String employeeName) {
        when(staffingQueries.findById("r1")).thenReturn(Optional.of(new StaffingRequestResponse("r1",
                new ProjectSummary("p1", "PAYROLL", "Payroll", ProjectStatus.ACTIVE),
                new EmployeeSummary("e-dev", employeeName, "Engineer"),
                action, action == StaffingAction.ASSIGN ? "Backend dev" : null,
                action == StaffingAction.ASSIGN ? 50 : null, status, "lena",
                Instant.parse("2026-09-24T10:15:00Z"), decidedBy, null, reason)));
    }

    private void givenRequest(StaffingAction action, StaffingRequestStatus status, String decidedBy, String reason) {
        givenRequest(action, status, decidedBy, reason, "Dev Kumar");
    }

    private List<Queued> queued(int expected) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> reference = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(expected)).enqueue(to.capture(), subject.capture(), body.capture(), reference.capture());

        return java.util.stream.IntStream.range(0, expected)
                .mapToObj(i -> new Queued(to.getAllValues().get(i), subject.getAllValues().get(i),
                        body.getAllValues().get(i), reference.getAllValues().get(i)))
                .toList();
    }

    private static void notify(StaffingNotifier notifier, StaffingRequestEvent.Type type) {
        notifier.onStaffingRequestEvent(new StaffingRequestEvent("r1", type));
    }

    @Test
    void newRequestGoesToTheHrMailbox() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("hr@example.com");
        assertThat(email.subject()).isEqualTo("[WorkSphere] Staffing request: add Dev Kumar on Payroll (PAYROLL)");
        assertThat(email.reference()).isEqualTo("staffing-request:r1:REQUESTED");
        assertThat(email.body())
                .contains("Lena Lead has requested")
                .contains("Add or update Dev Kumar: Backend dev, 50% allocation")
                .contains("2026-09-24 10:15 UTC")
                .contains("https://worksphere.example.com/staffing-requests/r1");
    }

    @Test
    void approvalTellsTheLeadAndTheEmployee() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.APPROVED, "hr.user", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);

        List<Queued> emails = queued(2);
        assertThat(emails.get(0).to()).isEqualTo("lena@example.com");
        assertThat(emails.get(0).subject()).startsWith("[WorkSphere] Approved: add Dev Kumar");
        assertThat(emails.get(0).body()).contains("approved by hr.user");

        assertThat(emails.get(1).to()).isEqualTo("dev@example.com");
        assertThat(emails.get(1).subject()).isEqualTo("[WorkSphere] Project update: Payroll (PAYROLL)");
        assertThat(emails.get(1).body())
                .contains("You have been staffed on Payroll (PAYROLL)")
                .contains("Role:       Backend dev")
                .contains("Allocation: 50% of your time")
                .contains("The project lead is Lena Lead");
    }

    @Test
    void approvedRemovalTellsTheEmployeeTheyWereRemoved() {
        givenRequest(StaffingAction.REMOVE, StaffingRequestStatus.APPROVED, "hr.user", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);

        Queued toEmployee = queued(2).get(1);
        assertThat(toEmployee.subject()).isEqualTo("[WorkSphere] You have been removed from Payroll (PAYROLL)");
        assertThat(toEmployee.body()).contains("You are no longer on Payroll (PAYROLL)");
    }

    @Test
    void rejectionTellsTheLeadWithTheReason() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.REJECTED, "hr.user", "Dev is fully booked");

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REJECTED);

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("lena@example.com");
        assertThat(email.subject()).startsWith("[WorkSphere] Rejected:");
        assertThat(email.body()).contains("rejected by hr.user").contains("Reason:  Dev is fully booked");
    }

    @Test
    void withdrawalTellsHr() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.CANCELLED, "lena", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.CANCELLED);

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("hr@example.com");
        assertThat(email.subject()).startsWith("[WorkSphere] Staffing request withdrawn:");
    }

    @Test
    void missingAddressesAreSkipped() {
        // No HR mailbox configured.
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null);
        notify(notifier(""), StaffingRequestEvent.Type.REQUESTED);
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), anyString());

        // The employee has no email (e.g. migrated from the old data): only the lead is emailed.
        when(employees.findById("e-dev")).thenReturn(Optional.of(employee("e-dev", "Dev Kumar", null)));
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.APPROVED, "hr.user", null);
        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);
        assertThat(queued(1).get(0).to()).isEqualTo("lena@example.com");
    }

    @Test
    void lineBreaksInNamesNeverReachTheSubjectHeader() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null,
                "Dev\r\nBcc: attacker@example.com");

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        assertThat(queued(1).get(0).subject()).doesNotContain("\r").doesNotContain("\n")
                .contains("Dev Bcc: attacker@example.com");
    }

    @Test
    void aProblemBuildingTheEmailNeverBlocksTheChange() {
        when(staffingQueries.findById("r1")).thenThrow(new IllegalStateException("unexpected data"));

        // Doesn't throw, so the staffing transaction still commits.
        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void anUnknownRequestQueuesNothing() {
        when(staffingQueries.findById("r1")).thenReturn(Optional.empty());

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }
}
