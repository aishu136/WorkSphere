package com.example.neo4j.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateUserRequest;
import com.example.neo4j.dto.UpdateEmployeeRequest;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.security.Role;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.UserService;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Audit trail against an embedded Neo4j. No test-level transaction here: each service call
 * commits or rolls back on its own, which is what proves a failed change leaves no audit event.
 */
@DataNeo4jTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, DepartmentService.class, UserService.class, EmployeeQueries.class,
        DepartmentQueries.class, ProjectQueries.class, AuditLog.class, AuditTrailTests.Passwords.class})
class AuditTrailTests {

    @TestConfiguration
    static class Passwords {
        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }

    private static final Neo4j neo4j = EmbeddedNeo4j.start();

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        EmbeddedNeo4j.register(registry, neo4j);
    }

    @AfterAll
    static void stopNeo4j() {
        neo4j.close();
    }

    @Autowired
    EmployeeService employees;

    @Autowired
    DepartmentService departments;

    @Autowired
    UserService users;

    @Autowired
    AuditLog auditLog;

    @Autowired
    Neo4jClient client;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @BeforeEach
    void cleanDatabase() {
        client.query("""
                MATCH (n) WHERE n:Employee OR n:Department OR n:AppUser OR n:AuditEvent OR n:Skill OR n:Company
                DETACH DELETE n
                """).run();
        actAs("priya.hr");
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    private static void actAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(username, null, "ROLE_HR"));
    }

    private String hire(String code, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                "Engineer", LocalDate.of(2024, 1, 15), null, managerId)).id();
    }

    private List<AuditEventResponse> history(AuditTargetType type, String id) {
        return auditLog.search(AuditFilter.forTarget(type, id), FIRST_PAGE).getContent();
    }

    private long count(String cypher) {
        return client.query(cypher).fetchAs(Long.class).one().orElseThrow();
    }

    @Test
    void createAndUpdateAreRecordedWithOnlyTheChangedFields() {
        String id = hire("E1", null);

        employees.update(id, new UpdateEmployeeRequest("Name E1", "new@example.com", "Engineer",
                LocalDate.of(2024, 1, 15)));
        // Same values again: nothing changes, so nothing is audited.
        employees.update(id, new UpdateEmployeeRequest("Name E1", "new@example.com", "Engineer",
                LocalDate.of(2024, 1, 15)));

        List<AuditEventResponse> history = history(AuditTargetType.EMPLOYEE, id);
        assertThat(history).extracting(AuditEventResponse::action)
                .containsExactly(AuditAction.EMPLOYEE_UPDATED, AuditAction.EMPLOYEE_CREATED);
        assertThat(history).extracting(AuditEventResponse::actor).containsOnly("priya.hr");

        assertThat(history.get(0).details()).containsOnlyKeys("email");
        assertThat(history.get(0).details().get("email"))
                .isEqualTo(Map.of("from", "e1@example.com", "to", "new@example.com"));

        assertThat(history.get(1).details())
                .containsEntry("employeeCode", "E1")
                .containsEntry("hireDate", "2024-01-15");
    }

    @Test
    void aFailedChangeLeavesNoAuditEvent() {
        // Created, then the manager lookup fails: the whole transaction rolls back.
        assertThatThrownBy(() -> hire("E1", "missing-manager")).isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("MATCH (e:Employee) RETURN count(e)")).isZero();
        assertThat(count("MATCH (a:AuditEvent) RETURN count(a)")).isZero();
    }

    @Test
    void relationshipChangesRecordBeforeAndAfter() {
        String a = hire("A", null);
        String b = hire("B", null);
        String dev = hire("DEV", a);

        employees.assignManager(dev, b);
        employees.assignManager(dev, b); // no-op

        List<AuditEventResponse> managerChanges = auditLog.search(new AuditFilter(null,
                AuditAction.EMPLOYEE_MANAGER_CHANGED, AuditTargetType.EMPLOYEE, dev, null, null), FIRST_PAGE)
                .getContent();
        assertThat(managerChanges).hasSize(2);
        assertThat(managerChanges.get(0).details().get("managerId")).isEqualTo(Map.of("from", a, "to", b));
        assertThat(((Map<?, ?>) managerChanges.get(1).details().get("managerId")).get("to")).isEqualTo(a);

        employees.terminate(dev);

        AuditEventResponse terminated = history(AuditTargetType.EMPLOYEE, dev).get(0);
        assertThat(terminated.action()).isEqualTo(AuditAction.EMPLOYEE_TERMINATED);
        assertThat(terminated.details())
                .containsEntry("previousManagerId", b)
                .containsEntry("previousStatus", "ACTIVE")
                .containsEntry("terminationDate", LocalDate.now().toString());
    }

    @Test
    void deletedDepartmentStaysInHistory() {
        String id = departments.create(new CreateDepartmentRequest("OPS", "Operations", null, null)).id();
        departments.delete(id);

        List<AuditEventResponse> history = history(AuditTargetType.DEPARTMENT, id);
        assertThat(history).extracting(AuditEventResponse::action)
                .containsExactly(AuditAction.DEPARTMENT_DELETED, AuditAction.DEPARTMENT_CREATED);
        assertThat(history.get(0).details()).containsEntry("code", "OPS").containsEntry("name", "Operations");
    }

    @Test
    void actionsWithoutALoggedInUserAreBySystemAndNeverStorePasswords() {
        SecurityContextHolder.clearContext();

        users.create(new CreateUserRequest("new.user", "a-very-secret-password", Set.of(Role.EMPLOYEE), null));

        AuditEventResponse created = history(AuditTargetType.USER, "new.user").get(0);
        assertThat(created.actor()).isEqualTo(AuditLog.SYSTEM_ACTOR);
        assertThat(created.details()).containsOnlyKeys("roles", "employeeId");

        String rawDetails = client.query("MATCH (a:AuditEvent {action: 'USER_CREATED'}) RETURN a.details")
                .fetchAs(String.class).one().orElseThrow();
        assertThat(rawDetails).doesNotContain("secret", "bcrypt", "password");
    }

    @Test
    void searchFiltersByActorActionAndTimeNewestFirst() {
        String e1 = hire("E1", null);
        actAs("ravi.hr");
        employees.changeStatus(e1, com.example.neo4j.entity.EmploymentStatus.ON_LEAVE);
        hire("E2", null);

        assertThat(auditLog.search(new AuditFilter("ravi.hr", null, null, null, null, null), FIRST_PAGE)
                .getTotalElements()).isEqualTo(2);
        assertThat(auditLog.search(new AuditFilter(null, AuditAction.EMPLOYEE_CREATED, null, null, null, null),
                FIRST_PAGE).getContent()).extracting(AuditEventResponse::actor)
                .containsExactly("ravi.hr", "priya.hr");

        OffsetDateTime later = OffsetDateTime.now().plusHours(1);
        assertThat(auditLog.search(new AuditFilter(null, null, null, null, later, null), FIRST_PAGE)
                .getTotalElements()).isZero();
        assertThat(auditLog.search(new AuditFilter(null, null, null, null, null, later), FIRST_PAGE)
                .getTotalElements()).isEqualTo(3);

        Page<AuditEventResponse> page = auditLog.search(new AuditFilter(null, null, null, null, null, null),
                PageRequest.of(1, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).action()).isEqualTo(AuditAction.EMPLOYEE_CREATED);
        assertThat(page.getContent().get(0).actor()).isEqualTo("priya.hr");
    }
}
