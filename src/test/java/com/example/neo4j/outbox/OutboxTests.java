package com.example.neo4j.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.notification.StaffingNotifier;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.repository.StaffingQueries;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.ProjectService;
import com.example.neo4j.service.StaffingService;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * The email outbox end to end against an embedded Neo4j: emails saved with the staffing change,
 * sent by the dispatcher, retried with back-off, given up on, retried by an admin, and cleaned up.
 *
 * No test-level transaction: every call really commits or rolls back. Scheduling isn't enabled in
 * this slice, so the dispatcher only runs when a test calls it, with a chosen "now".
 */
@DataNeo4jTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({StaffingService.class, ProjectService.class, EmployeeService.class, StaffingQueries.class,
        ProjectQueries.class, EmployeeQueries.class, AuditLog.class, StaffingNotifier.class,
        OutboxService.class, OutboxDispatcher.class})
@TestPropertySource(properties = {
        "app.notifications.hr-email=hr@example.com",
        "app.outbox.max-attempts=3"
})
class OutboxTests {

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
    OutboxService outbox;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxEmailRepository outboxRepository;

    @Autowired
    AppUserRepository users;

    @Autowired
    Neo4jClient client;

    private String dev, payroll;

    @BeforeEach
    void setUp() {
        client.query("""
                MATCH (n) WHERE n:Employee OR n:Project OR n:StaffingRequest OR n:AuditEvent OR n:AppUser
                    OR n:OutboxEmail
                DETACH DELETE n
                """).run();

        String lead = hire("LENA");
        dev = hire("DEV");
        payroll = projects.create(new CreateProjectRequest("PAYROLL", "Payroll", null, ProjectStatus.ACTIVE,
                null, null, null, lead)).id();

        AppUser lena = new AppUser();
        lena.setUsername("lena");
        lena.setPasswordHash("{noop}unused");
        lena.setRoles(List.of("EMPLOYEE"));
        lena.setEmployeeId(lead);
        users.save(lena);
    }

    private String hire(String code) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, null)).id();
    }

    private StaffingRequestResponse requestAssign(int allocation) {
        return staffing.request(payroll, new CreateStaffingRequest(dev, StaffingAction.ASSIGN, "Dev", allocation),
                "lena");
    }

    private List<OutboxEmail> outboxEmails() {
        return outboxRepository.findAll();
    }

    private OutboxEmail onlyEmail() {
        List<OutboxEmail> emails = outboxEmails();
        assertThat(emails).hasSize(1);
        return emails.get(0);
    }

    // ---- Saved with the change --------------------------------------------------------

    @Test
    void emailsAreSavedWithTheChangeAndSentByTheDispatcher() {
        requestAssign(50);

        // Saved, but nothing sent yet.
        OutboxEmail queued = onlyEmail();
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(queued.getRecipient()).isEqualTo("hr@example.com");
        assertThat(queued.getSubject()).startsWith("[WorkSphere] Staffing request: add Name DEV");
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        assertThat(dispatcher.dispatchDue(Instant.now())).isEqualTo(1);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("hr@example.com");
        assertThat(sent.getValue().getText()).contains("Name LENA has requested");

        OutboxEmail done = onlyEmail();
        assertThat(done.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getSentAt()).isNotNull();

        // Nothing left to send.
        assertThat(dispatcher.dispatchDue(Instant.now())).isZero();
    }

    @Test
    void aChangeThatRollsBackLeavesNoEmail() {
        StaffingRequestResponse pending = requestAssign(60);
        String other = projects.create(new CreateProjectRequest("OTHER", "Other", null, ProjectStatus.ACTIVE,
                null, null, null, null)).id();
        projects.assign(other, dev, new AssignmentRequest("Dev", 50));

        // Approving would put Dev at 110%, so the approval rolls back...
        assertThatThrownBy(() -> staffing.approve(pending.id(), "hr.user")).isInstanceOf(ConflictException.class);

        // ...and so does its email: only the original HR email exists.
        assertThat(outboxEmails()).extracting(OutboxEmail::getReference)
                .containsExactly("staffing-request:" + pending.id() + ":REQUESTED");
    }

    // ---- Retries ----------------------------------------------------------------------

    @Test
    void failedSendsAreRetriedWithBackOffUntilTheyGoThrough() {
        StaffingRequestResponse pending = requestAssign(50);
        staffing.approve(pending.id(), "hr.user");   // 1 HR + 2 approval emails
        assertThat(outboxEmails()).hasSize(3);

        Instant now = Instant.now();
        doThrow(new MailSendException("mail server down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(dispatcher.dispatchDue(now)).isZero();

        // The staffing change is unaffected; every email waits for its first retry in 1 minute.
        assertThat(staffing.findById(pending.id()).status()).isEqualTo(StaffingRequestStatus.APPROVED);
        assertThat(outboxEmails()).allSatisfy(email -> {
            assertThat(email.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(email.getAttempts()).isEqualTo(1);
            assertThat(email.getLastError()).contains("mail server down");
            assertThat(email.getNextAttemptAt()).isEqualTo(now.plus(Duration.ofMinutes(1)));
        });

        // Too early: nothing is attempted.
        reset(mailSender);
        assertThat(dispatcher.dispatchDue(now.plusSeconds(30))).isZero();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        // The server is back and the retry is due.
        assertThat(dispatcher.dispatchDue(now.plusSeconds(61))).isEqualTo(3);
        assertThat(outboxEmails()).allSatisfy(email -> {
            assertThat(email.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(email.getAttempts()).isEqualTo(2);
        });
    }

    @Test
    void afterTheLastAttemptItFailsUntilAnAdminRetriesIt() {
        requestAssign(50);
        doThrow(new MailSendException("mailbox unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

        // max-attempts is 3 in this test: attempts at t, t+1m and t+3m.
        Instant now = Instant.now();
        dispatcher.dispatchDue(now);
        dispatcher.dispatchDue(now.plus(Duration.ofMinutes(1)));
        dispatcher.dispatchDue(now.plus(Duration.ofMinutes(3)));

        OutboxEmail failed = onlyEmail();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getNextAttemptAt()).isNull();
        verify(mailSender, times(3)).send(any(SimpleMailMessage.class));

        // Given up: never picked up again on its own.
        assertThat(dispatcher.dispatchDue(now.plus(Duration.ofDays(1)))).isZero();
        assertThat(outbox.search(OutboxStatus.FAILED, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        // An admin retries it once the mailbox is fixed.
        reset(mailSender);
        OutboxEmailResponse retried = outbox.retry(failed.getId());
        assertThat(retried.status()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.attempts()).isZero();
        assertThat(retried.lastError()).contains("mailbox unavailable");

        assertThat(dispatcher.dispatchDue(Instant.now())).isEqualTo(1);
        assertThat(onlyEmail().getStatus()).isEqualTo(OutboxStatus.SENT);

        assertThatThrownBy(() -> outbox.retry(failed.getId())).isInstanceOf(ConflictException.class);
    }

    // ---- Claiming, no mail server, retention --------------------------------------------

    @Test
    void aClaimedEmailIsNotPickedUpByAnotherDispatcher() {
        requestAssign(50);
        String id = onlyEmail().getId();
        Instant now = Instant.now();

        assertThat(outbox.claim(id, now)).isPresent();

        // A second dispatcher at the same moment finds it no longer due...
        assertThat(outbox.claim(id, now)).isEmpty();
        assertThat(outbox.dueIds(now, 50)).isEmpty();

        // ...until the claim expires (e.g. the first instance crashed mid-send).
        assertThat(outbox.dueIds(now.plus(OutboxService.CLAIM_LEASE), 50)).containsExactly(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAMailServerEmailsWaitInsteadOfBeingLost() {
        requestAssign(50);

        ObjectProvider<JavaMailSender> noMailServer = mock(ObjectProvider.class);
        when(noMailServer.getIfAvailable()).thenReturn(null);
        OutboxDispatcher unconfigured = new OutboxDispatcher(outbox, noMailServer, "no-reply@test", 50,
                Duration.ofDays(30));

        assertThat(unconfigured.dispatchDue(Instant.now())).isZero();
        assertThat(onlyEmail().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(onlyEmail().getAttempts()).isZero();

        // Once a mail server is configured, it goes out.
        assertThat(dispatcher.dispatchDue(Instant.now())).isEqualTo(1);
    }

    @Test
    void sentEmailsAreDeletedAfterTheRetentionPeriodButOthersAreKept() {
        StaffingRequestResponse pending = requestAssign(50);
        dispatcher.dispatchDue(Instant.now());   // HR email SENT
        staffing.cancel(pending.id(), "lena");   // withdrawal email PENDING

        Instant muchLater = Instant.now().plus(Duration.ofDays(31));
        assertThat(outbox.deleteSentBefore(muchLater.minus(Duration.ofDays(30)))).isEqualTo(1);

        assertThat(onlyEmail().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(onlyEmail().getReference()).endsWith(":CANCELLED");
    }
}
