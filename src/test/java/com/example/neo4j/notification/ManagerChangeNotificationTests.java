package com.example.neo4j.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.outbox.OutboxEmail;
import com.example.neo4j.outbox.OutboxEmailRepository;
import com.example.neo4j.outbox.OutboxService;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Manager self-service notifications against an embedded Neo4j, with real commits and rollbacks:
 * emails land in the outbox together with the change, and only for changes a manager made.
 */
@DataNeo4jTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, EmployeeQueries.class, ProjectQueries.class, AuditLog.class,
        EmployeeChangeNotifier.class, OutboxService.class})
@TestPropertySource(properties = "app.notifications.hr-email=hr@example.com")
class ManagerChangeNotificationTests {

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
    EmployeeService employees;

    @Autowired
    OutboxEmailRepository outbox;

    @Autowired
    AppUserRepository users;

    @Autowired
    Neo4jClient client;

    // Chitra (login "chitra", plain EMPLOYEE role) manages Lena, who manages Dev.
    private String chitra, lena, dev;

    @BeforeEach
    void setUp() {
        client.query("""
                MATCH (n) WHERE n:Employee OR n:AuditEvent OR n:AppUser OR n:OutboxEmail OR n:Skill
                DETACH DELETE n
                """).run();

        actAs("hr.user", "ROLE_HR");
        chitra = hire("CHITRA", null);
        lena = hire("LENA", chitra);
        dev = hire("DEV", lena);

        AppUser login = new AppUser();
        login.setUsername("chitra");
        login.setPasswordHash("{noop}unused");
        login.setRoles(List.of("EMPLOYEE"));
        login.setEmployeeId(chitra);
        users.save(login);

        // Setting up the org was done by HR, so nothing was emailed.
        assertThat(outbox.findAll()).isEmpty();
    }

    private String hire(String code, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, managerId)).id();
    }

    private static void actAs(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(username, null, role));
    }

    private List<String> recipients() {
        return outbox.findAll().stream().map(OutboxEmail::getRecipient).sorted().toList();
    }

    @Test
    void aManagerPuttingSomeoneOnLeaveEmailsThemAndHr() {
        actAs("chitra", "ROLE_EMPLOYEE");
        employees.changeStatus(dev, EmploymentStatus.ON_LEAVE);

        assertThat(recipients()).containsExactly("dev@example.com", "hr@example.com");
        assertThat(outbox.findAll()).extracting(OutboxEmail::getSubject)
                .contains("[WorkSphere] Your status is now: On leave");
    }

    @Test
    void aManagerMovingAReportEmailsTheEmployeeTheNewManagerAndHr() {
        String other = hire("OTHER", chitra);

        actAs("chitra", "ROLE_EMPLOYEE");
        employees.assignManager(dev, other);

        assertThat(recipients()).containsExactly("dev@example.com", "hr@example.com", "other@example.com");
    }

    @Test
    void aManagerChangingSkillsOnlyEmailsTheEmployee() {
        actAs("chitra", "ROLE_EMPLOYEE");
        employees.addSkill(dev, new SkillRequest("Kafka"));
        // Adding the same skill again changes nothing, so it sends nothing.
        employees.addSkill(dev, new SkillRequest("kafka"));

        assertThat(recipients()).containsExactly("dev@example.com");
    }

    @Test
    void theSameChangeByHrSendsNothing() {
        actAs("hr.user", "ROLE_HR");
        employees.changeStatus(dev, EmploymentStatus.ON_LEAVE);
        employees.addSkill(dev, new SkillRequest("Kafka"));

        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aChangeThatFailsSendsNothing() {
        actAs("chitra", "ROLE_EMPLOYEE");

        // Lena reporting to Dev would create a loop, so the change is refused and rolled back.
        assertThatThrownBy(() -> employees.assignManager(lena, dev)).isInstanceOf(ConflictException.class);

        // A status that is already set is not a change.
        employees.changeStatus(dev, EmploymentStatus.ACTIVE);

        assertThat(outbox.findAll()).isEmpty();
    }
}
