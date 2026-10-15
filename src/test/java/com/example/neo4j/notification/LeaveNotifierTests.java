package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.LeaveRequestResponse;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.LeaveStatus;
import com.example.neo4j.entity.LeaveType;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.LeaveQueries;

/** Recipients and content of the leave emails put in the outbox. No database or mail server. */
class LeaveNotifierTests {

    record Queued(String to, String subject, String body) {
    }

    private LeaveQueries leaveQueries;
    private EmployeeQueries employeeQueries;
    private EmployeeRepository employees;
    private OutboxService outbox;

    @BeforeEach
    void setUp() {
        leaveQueries = mock(LeaveQueries.class);
        employeeQueries = mock(EmployeeQueries.class);
        employees = mock(EmployeeRepository.class);
        outbox = mock(OutboxService.class);

        // Dev reports to Mia.
        when(employees.findById("e-dev")).thenReturn(Optional.of(employee("e-dev", "Dev Kumar", "dev@example.com")));
        when(employees.findById("e-mia")).thenReturn(Optional.of(employee("e-mia", "Mia Manager", "mia@example.com")));
        givenManager(new EmployeeSummary("e-mia", "Mia Manager", "Team Lead"));
    }

    private static Employee employee(String id, String name, String email) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setName(name);
        employee.setEmail(email);
        return employee;
    }

    private void givenManager(EmployeeSummary manager) {
        when(employeeQueries.findById("e-dev")).thenReturn(Optional.of(new EmployeeResponse("e-dev", "E-DEV",
                "Dev Kumar", "dev@example.com", "Engineer", LocalDate.of(2024, 1, 15), EmploymentStatus.ACTIVE, null,
                null, manager, null, null, List.of(), 0, 0)));
    }

    private void givenLeave(LeaveStatus status, String decidedBy, String reason) {
        when(leaveQueries.findById("l1")).thenReturn(Optional.of(new LeaveRequestResponse("l1",
                new EmployeeSummary("e-dev", "Dev Kumar", "Engineer"), LeaveType.ANNUAL,
                LocalDate.of(2030, 3, 4), LocalDate.of(2030, 3, 8), 5, "Family visit", status, "dev",
                Instant.parse("2030-02-01T09:00:00Z"), decidedBy, null, reason)));
    }

    private LeaveNotifier notifier(String hrEmail) {
        return new LeaveNotifier(outbox, leaveQueries, employeeQueries, employees, hrEmail,
                "https://worksphere.example.com/");
    }

    private List<Queued> queued(int expected) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(expected)).enqueue(to.capture(), subject.capture(), body.capture(), any());

        return java.util.stream.IntStream.range(0, expected)
                .mapToObj(i -> new Queued(to.getAllValues().get(i), subject.getAllValues().get(i),
                        body.getAllValues().get(i)))
                .toList();
    }

    private static void notify(LeaveNotifier notifier, LeaveRequestEvent.Type type) {
        notifier.onLeaveRequestEvent(new LeaveRequestEvent("l1", type));
    }

    @Test
    void aNewRequestGoesToTheManager() {
        givenLeave(LeaveStatus.PENDING, null, null);

        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.REQUESTED);

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("mia@example.com");
        assertThat(email.subject()).isEqualTo("[WorkSphere] Leave request from Dev Kumar: Mon 4 Mar 2030 to Fri 8 Mar 2030");
        assertThat(email.body())
                .contains("Dev Kumar has asked for annual leave")
                .contains("Working days: 5")
                .contains("Note:         Family visit")
                .contains("https://worksphere.example.com/leave-requests/l1");
    }

    @Test
    void withoutAManagerHrIsAsked() {
        givenManager(null);
        givenLeave(LeaveStatus.PENDING, null, null);

        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.REQUESTED);

        assertThat(queued(1).get(0).to()).isEqualTo("hr@example.com");
    }

    @Test
    void theEmployeeHearsTheDecision() {
        givenLeave(LeaveStatus.REJECTED, "mia", "Release week");

        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.REJECTED);

        Queued email = queued(1).get(0);
        assertThat(email.to()).isEqualTo("dev@example.com");
        assertThat(email.subject()).startsWith("[WorkSphere] Leave not approved");
        assertThat(email.body()).contains("rejected by mia").contains("Reason: Release week");
    }

    @Test
    void cancellationsTellTheManagerAndApprovalsTheEmployee() {
        givenLeave(LeaveStatus.CANCELLED, "dev", null);
        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.CANCELLED);

        givenLeave(LeaveStatus.APPROVED, "mia", null);
        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.APPROVED);

        List<Queued> emails = queued(2);
        assertThat(emails.get(0).to()).isEqualTo("mia@example.com");
        assertThat(emails.get(0).body()).contains("has cancelled their annual leave");
        assertThat(emails.get(1).to()).isEqualTo("dev@example.com");
        assertThat(emails.get(1).body()).contains("was approved by mia");
    }

    @Test
    void aProblemBuildingTheEmailNeverBlocksTheChange() {
        when(leaveQueries.findById("l1")).thenThrow(new IllegalStateException("unexpected data"));

        notify(notifier("hr@example.com"), LeaveRequestEvent.Type.REQUESTED);

        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }
}
