package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.CreateStaffingRequest;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.StaffingQueries;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.ProjectService;
import com.example.neo4j.service.StaffingService;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Emails vs. transactions, against an embedded Neo4j. No test-level transaction: each service call
 * really commits or rolls back. (@Async is not enabled in this slice, so the after-commit listener
 * runs synchronously and can be checked straight away.)
 */
@DataNeo4jTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({StaffingService.class, ProjectService.class, EmployeeService.class, StaffingQueries.class,
        ProjectQueries.class, EmployeeQueries.class, AuditLog.class, StaffingNotifier.class})
@TestPropertySource(properties = "app.notifications.hr-email=hr@example.com")
class StaffingNotificationTransactionTests {

    private static final Neo4j neo4j = EmbeddedNeo4j.start();

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        EmbeddedNeo4j.register(registry, neo4j);
    }

    @AfterAll
    static void stopNeo4j() {
        neo4j.close();
    }

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    StaffingService staffing;

    @Autowired
    ProjectService projects;

    @Autowired
    EmployeeService employees;

    @Autowired
    Neo4jClient client;

    @Autowired
    com.example.neo4j.repository.AppUserRepository users;

    private String dev, payroll;

    @BeforeEach
    void setUp() {
        client.query("""
                MATCH (n) WHERE n:Employee OR n:Project OR n:StaffingRequest OR n:AuditEvent OR n:AppUser
                DETACH DELETE n
                """).run();

        String lead = hire("LENA");
        dev = hire("DEV");

        // Lena's login, so approval emails can reach her as the requesting lead.
        com.example.neo4j.entity.AppUser lena = new com.example.neo4j.entity.AppUser();
        lena.setUsername("lena");
        lena.setPasswordHash("{noop}unused");
        lena.setRoles(List.of("EMPLOYEE"));
        lena.setEmployeeId(lead);
        users.save(lena);
        payroll = projects.create(new CreateProjectRequest("PAYROLL", "Payroll", null, ProjectStatus.ACTIVE,
                null, null, null, lead)).id();
    }

    private String hire(String code) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, null)).id();
    }

    private StaffingRequestResponse requestAssign(int allocation) {
        return staffing.request(payroll, new CreateStaffingRequest(dev, StaffingAction.ASSIGN, "Dev", allocation),
                "lena");
    }

    private List<SimpleMailMessage> sentEmails() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void hrIsEmailedOnceTheRequestIsSaved() {
        requestAssign(50);

        assertThat(sentEmails()).singleElement()
                .satisfies(email -> assertThat(email.getTo()).containsExactly("hr@example.com"));
    }

    @Test
    void aChangeThatRollsBackSendsNoEmail() {
        StaffingRequestResponse pending = requestAssign(60);
        verify(mailSender).send(any(SimpleMailMessage.class)); // the HR email for the request itself
        clearInvocations(mailSender);

        // Dev gets booked 50% elsewhere, so approving 60% more fails and rolls back.
        String other = projects.create(new CreateProjectRequest("OTHER", "Other", null, ProjectStatus.ACTIVE,
                null, null, null, null)).id();
        projects.assign(other, dev, new AssignmentRequest("Dev", 50));

        assertThatThrownBy(() -> staffing.approve(pending.id(), "hr.user")).isInstanceOf(ConflictException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(staffing.findById(pending.id()).status()).isEqualTo(StaffingRequestStatus.PENDING);
    }

    @Test
    void aMailServerOutageDoesNotBreakTheWorkflow() {
        doThrow(new MailSendException("mail server down")).when(mailSender).send(any(SimpleMailMessage.class));

        StaffingRequestResponse pending = requestAssign(50);
        StaffingRequestResponse approved = staffing.approve(pending.id(), "hr.user");

        // Emails were attempted (HR on request; lead and employee on approval) and all failed...
        verify(mailSender, org.mockito.Mockito.times(3)).send(any(SimpleMailMessage.class));

        // ...yet both steps were saved.
        assertThat(approved.status()).isEqualTo(StaffingRequestStatus.APPROVED);
        assertThat(staffing.findById(pending.id()).status()).isEqualTo(StaffingRequestStatus.APPROVED);
    }
}
