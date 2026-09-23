package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.DepartmentResponse;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.UpdateEmployeeRequest;
import com.example.neo4j.entity.Company;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.CompanyRepository;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Service-level tests against an embedded Neo4j with the real migrations applied.
 * Each test runs in a transaction that is rolled back, so tests don't affect each other.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, DepartmentService.class, EmployeeQueries.class, DepartmentQueries.class,
        ProjectQueries.class, AuditLog.class})
class OrgStructureTests {

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
    CompanyRepository companyRepository;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    private EmployeeResponse hire(String code, String name) {
        return hire(code, name, null, null);
    }

    private EmployeeResponse hire(String code, String name, String departmentId, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, name, code.toLowerCase() + "@example.com",
                "Engineer", LocalDate.of(2024, 1, 15), departmentId, managerId));
    }

    private DepartmentResponse department(String code, String name, String parentId) {
        return departments.create(new CreateDepartmentRequest(code, name, null, parentId));
    }

    private static List<String> names(Page<? extends Object> page) {
        return page.getContent().stream()
                .map(o -> o instanceof EmployeeSummary s ? s.name() : ((EmployeeResponse) o).name())
                .toList();
    }

    // ---- Employees and reporting lines ------------------------------------------

    @Test
    void newHireIsPlacedInDepartmentUnderManager() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        EmployeeResponse lead = hire("E1", "Lena Lead", eng.id(), null);

        EmployeeResponse dev = hire("e2", "Dev One", eng.id(), lead.id());

        assertThat(dev.employeeCode()).isEqualTo("E2");
        assertThat(dev.status()).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(dev.department().code()).isEqualTo("ENG");
        assertThat(dev.manager().name()).isEqualTo("Lena Lead");
        assertThat(employees.findById(lead.id()).directReportCount()).isEqualTo(1);
    }

    @Test
    void employeeCodeAndEmailMustBeUnique() {
        hire("E1", "First");

        assertThatThrownBy(() -> hire("e1", "Other")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> employees.create(new CreateEmployeeRequest("E9", "Other", "E1@EXAMPLE.com",
                null, LocalDate.now(), null, null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void reportingCyclesAreRejected() {
        EmployeeResponse ceo = hire("E1", "Ceo");
        EmployeeResponse vp = hire("E2", "Vp", null, ceo.id());
        EmployeeResponse dev = hire("E3", "Dev", null, vp.id());

        assertThatThrownBy(() -> employees.assignManager(ceo.id(), dev.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> employees.assignManager(ceo.id(), ceo.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportingChainAndAllReportsFollowTheHierarchy() {
        EmployeeResponse ceo = hire("E1", "Ceo");
        EmployeeResponse vp = hire("E2", "Vp", null, ceo.id());
        EmployeeResponse dev = hire("E3", "Dev", null, vp.id());
        hire("E4", "Analyst", null, vp.id());

        assertThat(employees.reportingChain(dev.id())).extracting(EmployeeSummary::name)
                .containsExactly("Vp", "Ceo");
        assertThat(names(employees.directReports(ceo.id(), FIRST_PAGE))).containsExactly("Vp");

        Page<EmployeeSummary> all = employees.allReports(ceo.id(), PageRequest.of(0, 2));
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(names(all)).containsExactly("Analyst", "Dev");
    }

    @Test
    void reassigningManagerReplacesTheOldReportingLine() {
        EmployeeResponse a = hire("E1", "Alpha");
        EmployeeResponse b = hire("E2", "Beta");
        EmployeeResponse dev = hire("E3", "Dev", null, a.id());

        employees.assignManager(dev.id(), b.id());

        assertThat(employees.findById(dev.id()).manager().name()).isEqualTo("Beta");
        assertThat(employees.directReports(a.id(), FIRST_PAGE).getTotalElements()).isZero();
        assertThat(employees.reportingChain(dev.id())).hasSize(1);
    }

    @Test
    void directoryFiltersCombine() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        EmployeeResponse priya = hire("E1", "Priya Sharma", eng.id(), null);
        hire("E2", "Pritam Das", null, null);
        hire("E3", "Asha Rao", eng.id(), null);

        employees.addSkill(priya.id(), new SkillRequest("Java"));
        Company acme = new Company();
        acme.setName("Acme");
        employees.changeCompany(priya.id(), companyRepository.save(acme).getId());

        assertThat(names(employees.search(EmployeeFilter.byName("PRI"), FIRST_PAGE)))
                .containsExactly("Pritam Das", "Priya Sharma");
        assertThat(names(employees.search(new EmployeeFilter("pri", null, eng.id(), null, null, null, null), FIRST_PAGE)))
                .containsExactly("Priya Sharma");
        assertThat(names(employees.search(new EmployeeFilter(null, null, null, "JAVA", "acme", null, null), FIRST_PAGE)))
                .containsExactly("Priya Sharma");

        Page<EmployeeResponse> page = employees.search(new EmployeeFilter(null, null, null, null, null, null, null),
                PageRequest.of(1, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(names(page)).containsExactly("Priya Sharma");
    }

    @Test
    void updateKeepsEmailUnique() {
        hire("E1", "One");
        EmployeeResponse two = hire("E2", "Two");

        assertThatThrownBy(() -> employees.update(two.id(),
                new UpdateEmployeeRequest("Two", "e1@example.com", null, LocalDate.now())))
                .isInstanceOf(ConflictException.class);

        EmployeeResponse renamed = employees.update(two.id(),
                new UpdateEmployeeRequest("Twain", "e2@example.com", "Architect", LocalDate.of(2020, 5, 1)));
        assertThat(renamed.name()).isEqualTo("Twain");
        assertThat(names(employees.search(EmployeeFilter.byName("twa"), FIRST_PAGE))).containsExactly("Twain");
    }

    // ---- Termination ------------------------------------------------------------

    @Test
    void terminationRequiresReportsToBeReassignedFirst() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        EmployeeResponse boss = hire("E1", "Boss", eng.id(), null);
        EmployeeResponse lead = hire("E2", "Lead", eng.id(), boss.id());
        EmployeeResponse dev = hire("E3", "Dev", eng.id(), lead.id());

        assertThatThrownBy(() -> employees.terminate(lead.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1 direct report");

        employees.assignManager(dev.id(), boss.id());
        EmployeeResponse terminated = employees.terminate(lead.id());

        assertThat(terminated.status()).isEqualTo(EmploymentStatus.TERMINATED);
        assertThat(terminated.terminationDate()).isEqualTo(LocalDate.now());
        assertThat(terminated.manager()).isNull();
        assertThat(terminated.department()).isNull();

        // Record is kept but excluded from active searches, and can't be changed further.
        assertThat(names(employees.search(new EmployeeFilter(null, EmploymentStatus.ACTIVE, null, null, null, null, null),
                FIRST_PAGE))).containsExactly("Boss", "Dev");
        assertThatThrownBy(() -> employees.assignManager(dev.id(), lead.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> employees.changeStatus(lead.id(), EmploymentStatus.ACTIVE))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void statusEndpointCannotTerminate() {
        EmployeeResponse e = hire("E1", "One");

        assertThat(employees.changeStatus(e.id(), EmploymentStatus.ON_LEAVE).status())
                .isEqualTo(EmploymentStatus.ON_LEAVE);
        assertThatThrownBy(() -> employees.changeStatus(e.id(), EmploymentStatus.TERMINATED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void departmentHeadCannotBeTerminated() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        EmployeeResponse head = hire("E1", "Head");
        departments.setHead(eng.id(), head.id());

        assertThatThrownBy(() -> employees.terminate(head.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("department head");
    }

    // ---- Departments ------------------------------------------------------------

    @Test
    void departmentTreeRejectsCyclesAndCountsMembers() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        DepartmentResponse backend = department("BE", "Backend", eng.id());
        DepartmentResponse payments = department("PAY", "Payments", backend.id());

        hire("E1", "Eng Person", eng.id(), null);
        hire("E2", "Backend Person", backend.id(), null);
        hire("E3", "Payments Person", payments.id(), null);

        assertThatThrownBy(() -> departments.setParent(eng.id(), payments.id()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> departments.setParent(eng.id(), eng.id()))
                .isInstanceOf(IllegalArgumentException.class);

        DepartmentResponse engView = departments.findById(eng.id());
        assertThat(engView.memberCount()).isEqualTo(1);
        assertThat(engView.subDepartmentCount()).isEqualTo(1);
        assertThat(departments.findById(payments.id()).parent().code()).isEqualTo("BE");

        assertThat(departments.members(eng.id(), false, FIRST_PAGE).getTotalElements()).isEqualTo(1);
        assertThat(departments.members(eng.id(), true, FIRST_PAGE).getTotalElements()).isEqualTo(3);

        assertThat(departments.findAll(true, FIRST_PAGE).getContent())
                .extracting(DepartmentResponse::code).containsExactly("ENG");
        assertThat(departments.subDepartments(backend.id(), FIRST_PAGE).getContent())
                .extracting(DepartmentResponse::code).containsExactly("PAY");
    }

    @Test
    void onlyEmptyDepartmentsCanBeDeleted() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        DepartmentResponse empty = department("OPS", "Operations", eng.id());

        assertThatThrownBy(() -> departments.delete(eng.id())).isInstanceOf(ConflictException.class);

        departments.delete(empty.id());
        assertThatThrownBy(() -> departments.findById(empty.id())).isInstanceOf(ResourceNotFoundException.class);

        departments.delete(eng.id());
    }

    @Test
    void departmentCodeMustBeUniqueAndHeadIsShown() {
        DepartmentResponse eng = department("ENG", "Engineering", null);
        EmployeeResponse head = hire("E1", "Head Person", null, null);

        assertThatThrownBy(() -> department("eng", "Duplicate", null)).isInstanceOf(ConflictException.class);

        assertThat(departments.setHead(eng.id(), head.id()).head().name()).isEqualTo("Head Person");
        assertThat(departments.removeHead(eng.id()).head()).isNull();
    }
}
