package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.Employee;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeRepository;
import com.example.neo4j.repository.StaffingQueries;

/** Recipients and content of staffing emails, with a mocked mail server and no database. */
class StaffingNotifierTests {

    private StaffingQueries staffingQueries;
    private AppUserRepository users;
    private EmployeeRepository employees;
    private JavaMailSender sender;
    private ObjectProvider<JavaMailSender> senderProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        staffingQueries = mock(StaffingQueries.class);
        users = mock(AppUserRepository.class);
        employees = mock(EmployeeRepository.class);
        sender = mock(JavaMailSender.class);
        senderProvider = mock(ObjectProvider.class);
        when(senderProvider.getIfAvailable()).thenReturn(sender);

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
        return new StaffingNotifier(senderProvider, staffingQueries, users, employees,
                "no-reply@worksphere.test", hrEmail, "https://worksphere.example.com/");
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

    private List<SimpleMailMessage> sent(int expected) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    private static void notify(StaffingNotifier notifier, StaffingRequestEvent.Type type) {
        notifier.onStaffingRequestEvent(new StaffingRequestEvent("r1", type));
    }

    @Test
    void newRequestGoesToTheHrMailbox() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        SimpleMailMessage email = sent(1).get(0);
        assertThat(email.getTo()).containsExactly("hr@example.com");
        assertThat(email.getFrom()).isEqualTo("no-reply@worksphere.test");
        assertThat(email.getSubject()).isEqualTo("[WorkSphere] Staffing request: add Dev Kumar on Payroll (PAYROLL)");
        assertThat(email.getText())
                .contains("Lena Lead has requested")
                .contains("Add or update Dev Kumar: Backend dev, 50% allocation")
                .contains("2026-09-24 10:15 UTC")
                .contains("https://worksphere.example.com/staffing-requests/r1");
    }

    @Test
    void approvalTellsTheLeadAndTheEmployee() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.APPROVED, "hr.user", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);

        List<SimpleMailMessage> emails = sent(2);
        assertThat(emails.get(0).getTo()).containsExactly("lena@example.com");
        assertThat(emails.get(0).getSubject()).startsWith("[WorkSphere] Approved: add Dev Kumar");
        assertThat(emails.get(0).getText()).contains("approved by hr.user");

        assertThat(emails.get(1).getTo()).containsExactly("dev@example.com");
        assertThat(emails.get(1).getSubject()).isEqualTo("[WorkSphere] Project update: Payroll (PAYROLL)");
        assertThat(emails.get(1).getText())
                .contains("You have been staffed on Payroll (PAYROLL)")
                .contains("Role:       Backend dev")
                .contains("Allocation: 50% of your time")
                .contains("The project lead is Lena Lead");
    }

    @Test
    void approvedRemovalTellsTheEmployeeTheyWereRemoved() {
        givenRequest(StaffingAction.REMOVE, StaffingRequestStatus.APPROVED, "hr.user", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);

        SimpleMailMessage toEmployee = sent(2).get(1);
        assertThat(toEmployee.getSubject()).isEqualTo("[WorkSphere] You have been removed from Payroll (PAYROLL)");
        assertThat(toEmployee.getText()).contains("You are no longer on Payroll (PAYROLL)");
    }

    @Test
    void rejectionTellsTheLeadWithTheReason() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.REJECTED, "hr.user", "Dev is fully booked");

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REJECTED);

        SimpleMailMessage email = sent(1).get(0);
        assertThat(email.getTo()).containsExactly("lena@example.com");
        assertThat(email.getSubject()).startsWith("[WorkSphere] Rejected:");
        assertThat(email.getText()).contains("rejected by hr.user").contains("Reason:  Dev is fully booked");
    }

    @Test
    void withdrawalTellsHr() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.CANCELLED, "lena", null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.CANCELLED);

        SimpleMailMessage email = sent(1).get(0);
        assertThat(email.getTo()).containsExactly("hr@example.com");
        assertThat(email.getSubject()).startsWith("[WorkSphere] Staffing request withdrawn:");
    }

    @Test
    void missingAddressesAreSkipped() {
        // No HR mailbox configured.
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null);
        notify(notifier(""), StaffingRequestEvent.Type.REQUESTED);
        verify(sender, never()).send(any(SimpleMailMessage.class));

        // The employee has no email (e.g. migrated from the old data): only the lead is emailed.
        when(employees.findById("e-dev")).thenReturn(Optional.of(employee("e-dev", "Dev Kumar", null)));
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.APPROVED, "hr.user", null);
        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);
        assertThat(sent(1).get(0).getTo()).containsExactly("lena@example.com");
    }

    @Test
    void withoutAMailServerEmailsAreOnlyLogged() {
        when(senderProvider.getIfAvailable()).thenReturn(null);
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null);

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void oneFailedRecipientDoesNotStopTheOthersOrThrow() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.APPROVED, "hr.user", null);
        doThrow(new MailSendException("mail server down"))
                .doNothing()
                .when(sender).send(any(SimpleMailMessage.class));

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.APPROVED);

        assertThat(sent(2)).extracting(m -> m.getTo()[0]).containsExactly("lena@example.com", "dev@example.com");
    }

    @Test
    void lineBreaksInNamesNeverReachTheSubjectHeader() {
        givenRequest(StaffingAction.ASSIGN, StaffingRequestStatus.PENDING, null, null,
                "Dev\r\nBcc: attacker@example.com");

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        assertThat(sent(1).get(0).getSubject()).doesNotContain("\r").doesNotContain("\n")
                .contains("Dev Bcc: attacker@example.com");
    }

    @Test
    void anUnknownRequestSendsNothing() {
        when(staffingQueries.findById("r1")).thenReturn(Optional.empty());

        notify(notifier("hr@example.com"), StaffingRequestEvent.Type.REQUESTED);

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }
}
