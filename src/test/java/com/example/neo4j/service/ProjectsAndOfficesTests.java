package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateOfficeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeProjectsResponse;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.OfficeResponse;
import com.example.neo4j.dto.ProjectResponse;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.OfficeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Offices, projects and allocation rules against an embedded Neo4j with migrations applied.
 * Each test runs in a transaction that is rolled back.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, DepartmentService.class, OfficeService.class, ProjectService.class,
        EmployeeQueries.class, DepartmentQueries.class, OfficeQueries.class, ProjectQueries.class, AuditLog.class})
class ProjectsAndOfficesTests {

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
    OfficeService offices;

    @Autowired
    ProjectService projects;

    @Autowired
    AuditLog auditLog;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    private String hire(String code) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                "Engineer", LocalDate.of(2024, 1, 15), null, null)).id();
    }

    private String project(String code, ProjectStatus status) {
        return projects.create(new CreateProjectRequest(code, "Project " + code, null, status, null, null, null, null))
                .id();
    }

    private String office(String code, String city) {
        return offices.create(new CreateOfficeRequest(code, city + " office", city, "India", null, 200)).id();
    }

    private void assign(String projectId, String employeeId, int allocation) {
        projects.assign(projectId, employeeId, new AssignmentRequest("Developer", allocation));
    }

    private int allocationOf(String employeeId) {
        return employees.findById(employeeId).allocationPercent();
    }

    private List<String> names(List<EmployeeResponse> list) {
        return list.stream().map(EmployeeResponse::name).toList();
    }

    // ---- Offices ----------------------------------------------------------------

    @Test
    void employeesCanBePlacedInOfficesAndFilteredByThem() {
        String blr = office("BLR-01", "Bengaluru");
        String chn = office("CHN-01", "Chennai");
        String a = hire("A");
        hire("B");

        assertThatThrownBy(() -> office("blr-01", "Bengaluru")).isInstanceOf(ConflictException.class);

        EmployeeResponse placed = employees.assignOffice(a, blr);
        assertThat(placed.office().code()).isEqualTo("BLR-01");
        assertThat(placed.office().city()).isEqualTo("Bengaluru");

        assertThat(names(employees.search(new EmployeeFilter(null, null, null, null, null, blr, null), FIRST_PAGE)
                .getContent())).containsExactly("Name A");
        assertThat(offices.findById(blr).employeeCount()).isEqualTo(1);
        assertThat(offices.search("chenn", FIRST_PAGE).getContent()).extracting(OfficeResponse::id)
                .containsExactly(chn);

        // Moving offices replaces the link.
        assertThat(employees.assignOffice(a, chn).office().code()).isEqualTo("CHN-01");
        assertThat(offices.findById(blr).employeeCount()).isZero();
    }

    @Test
    void onlyEmptyOfficesCanBeDeleted() {
        String blr = office("BLR-01", "Bengaluru");
        String a = hire("A");
        employees.assignOffice(a, blr);

        assertThatThrownBy(() -> offices.delete(blr)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("1 employee");

        employees.removeOffice(a);
        offices.delete(blr);

        List<AuditEventResponse> history = auditLog.search(AuditFilter.forTarget(AuditTargetType.OFFICE, blr),
                FIRST_PAGE).getContent();
        assertThat(history.get(0).action()).isEqualTo(AuditAction.OFFICE_DELETED);
        assertThat(history.get(0).details()).containsEntry("code", "BLR-01");
    }

    // ---- Allocation -------------------------------------------------------------

    @Test
    void allocationAcrossOpenProjectsCannotExceed100() {
        String dev = hire("DEV");
        String p1 = project("P1", ProjectStatus.ACTIVE);
        String p2 = project("P2", ProjectStatus.ACTIVE);
        String p3 = project("P3", ProjectStatus.PLANNED);

        assign(p1, dev, 60);
        assign(p2, dev, 40);
        assertThat(allocationOf(dev)).isEqualTo(100);

        assertThatThrownBy(() -> assign(p3, dev, 10))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already 100% allocated");

        // Changing an existing assignment doesn't count its old value twice.
        assign(p1, dev, 50);
        assign(p3, dev, 10);
        assertThat(allocationOf(dev)).isEqualTo(100);

        EmployeeProjectsResponse projectsOfDev = projects.projectsOf(dev);
        assertThat(projectsOfDev.allocationPercent()).isEqualTo(100);
        assertThat(projectsOfDev.assignments()).hasSize(3);
    }

    @Test
    void finishedProjectsDontCountAndReopeningIsChecked() {
        String dev = hire("DEV");
        String old = project("OLD", ProjectStatus.ACTIVE);
        String next = project("NEXT", ProjectStatus.ACTIVE);

        assign(old, dev, 60);
        projects.changeStatus(old, ProjectStatus.COMPLETED);
        assertThat(allocationOf(dev)).isZero();

        // Freed capacity can be used elsewhere.
        assign(next, dev, 70);

        // Reopening would put the developer at 130%.
        assertThatThrownBy(() -> projects.changeStatus(old, ProjectStatus.ACTIVE))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Name DEV");
        assertThat(projects.findById(old).status()).isEqualTo(ProjectStatus.COMPLETED);

        // Nobody can join a finished project.
        String other = hire("OTHER");
        assertThatThrownBy(() -> assign(old, other, 10)).isInstanceOf(ConflictException.class);

        // The finished assignment is still listed as history, after the open one.
        assertThat(projects.projectsOf(dev).assignments()).extracting(a -> a.project().code())
                .containsExactly("NEXT", "OLD");
    }

    @Test
    void availabilitySearchFindsPeopleWithFreeCapacity() {
        String busy = hire("BUSY");
        String free = hire("FREE");
        String other = hire("OTHER");
        employees.addSkill(busy, new SkillRequest("Java"));
        employees.addSkill(free, new SkillRequest("Java"));

        String p = project("P", ProjectStatus.ACTIVE);
        assign(p, busy, 80);
        assign(p, free, 20);

        List<EmployeeResponse> available = employees.search(
                new EmployeeFilter(null, null, null, "java", null, null, 50), FIRST_PAGE).getContent();
        assertThat(names(available)).containsExactly("Name FREE");
        assertThat(available.get(0).allocationPercent()).isEqualTo(20);

        // With no skill filter, the person on no projects (0%) counts as available too.
        assertThat(names(employees.search(new EmployeeFilter(null, null, null, null, null, null, 50), FIRST_PAGE)
                .getContent())).containsExactly("Name FREE", "Name OTHER");
        assertThat(other).isNotNull();
    }

    // ---- Projects ---------------------------------------------------------------

    @Test
    void projectLifecycleLeadDepartmentAndMembership() {
        String eng = departments.create(new CreateDepartmentRequest("ENG", "Engineering", null, null)).id();
        String lead = hire("LEAD");
        String dev = hire("DEV");

        assertThatThrownBy(() -> projects.create(new CreateProjectRequest("BAD", "Bad", null, null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 1, 1), null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        ProjectResponse created = projects.create(new CreateProjectRequest("pay-2026", "Payroll revamp", "New payroll",
                ProjectStatus.ACTIVE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), eng, lead));
        String p = created.id();
        assertThat(created.code()).isEqualTo("PAY-2026");
        assertThat(created.department().code()).isEqualTo("ENG");
        assertThat(created.lead().name()).isEqualTo("Name LEAD");

        assertThatThrownBy(() -> projects.create(new CreateProjectRequest("PAY-2026", "Dup", null, null, null, null,
                null, null))).isInstanceOf(ConflictException.class);

        assign(p, dev, 50);
        projects.assign(p, dev, new AssignmentRequest("Tech lead", 60));
        assertThat(projects.members(p, FIRST_PAGE).getContent()).singleElement()
                .satisfies(m -> {
                    assertThat(m.role()).isEqualTo("Tech lead");
                    assertThat(m.allocationPercent()).isEqualTo(60);
                    assertThat(m.since()).isEqualTo(LocalDate.now());
                });

        assertThat(projects.search("payroll", ProjectStatus.ACTIVE, eng, FIRST_PAGE).getTotalElements()).isEqualTo(1);
        assertThat(projects.search(null, ProjectStatus.COMPLETED, null, FIRST_PAGE).getTotalElements()).isZero();

        // A project with members is kept as history; cancel it instead of deleting.
        assertThatThrownBy(() -> projects.delete(p)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("CANCELLED");

        projects.unassign(p, dev);
        assertThat(allocationOf(dev)).isZero();

        List<AuditEventResponse> history = auditLog.search(AuditFilter.forTarget(AuditTargetType.PROJECT, p),
                FIRST_PAGE).getContent();
        assertThat(history).extracting(AuditEventResponse::action).containsSubsequence(
                AuditAction.PROJECT_MEMBER_REMOVED, AuditAction.PROJECT_MEMBER_UPDATED,
                AuditAction.PROJECT_MEMBER_ADDED, AuditAction.PROJECT_LEAD_CHANGED, AuditAction.PROJECT_CREATED);
        assertThat(history.get(1).details())
                .containsEntry("employeeId", dev)
                .containsEntry("allocationPercent", Map.of("from", 50, "to", 60))
                .containsEntry("role", Map.of("from", "Developer", "to", "Tech lead"));

        projects.delete(p);
    }

    // ---- Termination ------------------------------------------------------------

    @Test
    void terminationHandlesProjectsAndOffices() {
        String dev = hire("DEV");
        String blr = office("BLR-01", "Bengaluru");
        employees.assignOffice(dev, blr);

        String open = project("OPEN", ProjectStatus.ACTIVE);
        String done = project("DONE", ProjectStatus.ACTIVE);
        assign(open, dev, 50);
        assign(done, dev, 30);
        projects.changeStatus(done, ProjectStatus.COMPLETED);

        projects.setLead(open, dev);
        assertThatThrownBy(() -> employees.terminate(dev))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("lead");

        projects.removeLead(open);
        EmployeeResponse terminated = employees.terminate(dev);

        assertThat(terminated.office()).isNull();
        assertThat(terminated.allocationPercent()).isZero();
        // Open assignment removed; the finished one is kept as history.
        assertThat(projects.projectsOf(dev).assignments()).extracting(a -> a.project().code())
                .containsExactly("DONE");

        AuditEventResponse event = auditLog.search(AuditFilter.forTarget(AuditTargetType.EMPLOYEE, dev), FIRST_PAGE)
                .getContent().get(0);
        assertThat(event.action()).isEqualTo(AuditAction.EMPLOYEE_TERMINATED);
        assertThat(event.details())
                .containsEntry("previousOfficeId", blr)
                .containsEntry("removedFromProjectIds", List.of(open));

        assertThatThrownBy(() -> assign(open, dev, 10)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> projects.setLead(open, dev)).isInstanceOf(ConflictException.class);
    }
}
