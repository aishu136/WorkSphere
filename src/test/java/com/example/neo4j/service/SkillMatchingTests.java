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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CandidateResponse;
import com.example.neo4j.dto.CandidateResponse.SkillMatch;
import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.DepartmentQueries;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Skill levels, project required skills and candidate matching against an embedded Neo4j
 * with migrations applied. Each test runs in a transaction that is rolled back.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, DepartmentService.class, ProjectService.class,
        EmployeeQueries.class, DepartmentQueries.class, ProjectQueries.class, AuditLog.class})
class SkillMatchingTests {

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
    ProjectService projects;

    @Autowired
    AuditLog auditLog;

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    private String hire(String code, String name) {
        return employees.create(new CreateEmployeeRequest(code, name, code.toLowerCase() + "@example.com",
                "Engineer", LocalDate.of(2024, 1, 15), null, null)).id();
    }

    private String project(String code, ProjectStatus status, String departmentId) {
        return projects.create(new CreateProjectRequest(code, "Project " + code, null, status, null, null,
                departmentId, null)).id();
    }

    private void skills(String employeeId, SkillRequest... skills) {
        employees.replaceSkills(employeeId, List.of(skills));
    }

    private static SkillRequest skill(String name, Integer level) {
        return new SkillRequest(name, level);
    }

    private static Map<String, Integer> levels(List<SkillResponse> skills) {
        return skills.stream().collect(java.util.stream.Collectors.toMap(SkillResponse::name, SkillResponse::level));
    }

    private List<AuditEventResponse> history(AuditAction action, String targetId) {
        AuditTargetType type = action == AuditAction.PROJECT_SKILLS_CHANGED
                ? AuditTargetType.PROJECT : AuditTargetType.EMPLOYEE;
        return auditLog.search(new AuditFilter(null, action, type, targetId, null, null), FIRST_PAGE).getContent();
    }

    // ---- Skill levels -------------------------------------------------------------

    @Test
    void skillLevelsDefaultChangeAndAreAudited() {
        String dev = hire("DEV", "Dev Kumar");

        // No level: a new skill is intermediate.
        assertThat(levels(employees.addSkill(dev, skill("Java", null)).skills())).containsExactly(Map.entry("Java", 3));

        // Adding it again with a level changes the level; without one it keeps it.
        assertThat(levels(employees.addSkill(dev, skill("java", 5)).skills())).containsExactly(Map.entry("Java", 5));
        employees.addSkill(dev, skill("JAVA", null));
        assertThat(history(AuditAction.EMPLOYEE_SKILLS_CHANGED, dev)).hasSize(2);

        // Replacing keeps levels that aren't given, adds new skills and drops the rest.
        employees.addSkill(dev, skill("Go", 2));
        EmployeeResponse replaced = employees.replaceSkills(dev, List.of(skill("Java", null), skill("Kafka", 4)));
        assertThat(levels(replaced.skills())).containsOnly(Map.entry("Java", 5), Map.entry("Kafka", 4));

        AuditEventResponse latest = history(AuditAction.EMPLOYEE_SKILLS_CHANGED, dev).get(0);
        assertThat(latest.details())
                .containsEntry("added", List.of("Kafka"))
                .containsEntry("removed", List.of("Go"))
                .containsEntry("levelChanged", List.of())
                .containsEntry("levels", Map.of("Kafka", 4));

        employees.addSkill(dev, skill("Kafka", 2));
        assertThat(history(AuditAction.EMPLOYEE_SKILLS_CHANGED, dev).get(0).details())
                .containsEntry("levelChanged", List.of("Kafka: 4 -> 2"));

        String kafkaId = replaced.skills().stream().filter(s -> s.name().equals("Kafka")).findFirst().orElseThrow().id();
        assertThat(levels(employees.removeSkill(dev, kafkaId).skills())).containsOnlyKeys("Java");
    }

    @Test
    void directoryCanFilterByMinimumSkillLevel() {
        String junior = hire("JR", "Junior Java");
        String senior = hire("SR", "Senior Java");
        skills(junior, skill("Java", 2));
        skills(senior, skill("Java", 4));

        assertThat(names(employees.search(new EmployeeFilter(null, null, null, "java", 3, null, null, null), FIRST_PAGE)))
                .containsExactly("Senior Java");
        assertThat(names(employees.search(new EmployeeFilter(null, null, null, "java", null, null, null, null), FIRST_PAGE)))
                .containsExactly("Junior Java", "Senior Java");

        assertThatThrownBy(() -> employees.search(new EmployeeFilter(null, null, null, null, 3, null, null, null),
                FIRST_PAGE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Required skills ---------------------------------------------------------

    @Test
    void projectsKeepTheirRequiredSkills() {
        String payroll = project("PAYROLL", ProjectStatus.ACTIVE, null);

        projects.replaceRequiredSkills(payroll, List.of(skill("Java", 4), skill("Kafka", null)));
        assertThat(levels(projects.findById(payroll).requiredSkills()))
                .containsOnly(Map.entry("Java", 4), Map.entry("Kafka", 3));

        // Required skills share skill nodes with employees' skills.
        String dev = hire("DEV", "Dev Kumar");
        String javaId = employees.addSkill(dev, skill("JAVA", 1)).skills().get(0).id();
        assertThat(projects.findById(payroll).requiredSkills()).extracting(SkillResponse::id).contains(javaId);

        projects.addRequiredSkill(payroll, skill("kafka", 5));
        assertThat(levels(projects.removeRequiredSkill(payroll, javaId).requiredSkills()))
                .containsExactly(Map.entry("Kafka", 5));

        assertThat(history(AuditAction.PROJECT_SKILLS_CHANGED, payroll)).hasSize(3);
        // Changing nothing records nothing.
        projects.addRequiredSkill(payroll, skill("Kafka", null));
        assertThat(history(AuditAction.PROJECT_SKILLS_CHANGED, payroll)).hasSize(3);
    }

    // ---- Matching -----------------------------------------------------------------

    @Test
    void candidatesAreRankedBySkillsThenDepartmentThenFreeCapacity() {
        String engineering = departments.create(new CreateDepartmentRequest("ENG", "Engineering", null, null)).id();
        String platform = departments.create(new CreateDepartmentRequest("PLAT", "Platform", null, engineering)).id();
        String sales = departments.create(new CreateDepartmentRequest("SALES", "Sales", null, null)).id();

        String payroll = project("PAYROLL", ProjectStatus.ACTIVE, engineering);
        projects.replaceRequiredSkills(payroll, List.of(skill("Java", 4), skill("Kafka", 3)));
        String other = project("OTHER", ProjectStatus.ACTIVE, null);

        // Meets both levels, in a sub-department of the owning one, half booked.
        String insider = hire("IN", "Insider");
        skills(insider, skill("Java", 4), skill("Kafka", 3));
        employees.assignDepartment(insider, platform);
        projects.assign(other, insider, new AssignmentRequest("Dev", 50));

        // Exceeds both levels, outside the department, fully free.
        String expert = hire("EX", "Expert");
        skills(expert, skill("Java", 5), skill("Kafka", 5));
        employees.assignDepartment(expert, sales);

        // Java below the level, no Kafka: (2/4 + 0) / 2 = 25%.
        String partial = hire("PA", "Partial");
        skills(partial, skill("Java", 2), skill("Python", 5));

        // Left out: fully booked, already on the project, on leave, no required skill.
        String busy = hire("BU", "Busy");
        skills(busy, skill("Java", 5), skill("Kafka", 5));
        projects.assign(other, busy, new AssignmentRequest("Dev", 100));
        String member = hire("ME", "Member");
        skills(member, skill("Java", 5));
        projects.assign(payroll, member, new AssignmentRequest("Dev", 20));
        String away = hire("AW", "Away");
        skills(away, skill("Java", 5));
        employees.changeStatus(away, EmploymentStatus.ON_LEAVE);
        String unrelated = hire("UN", "Unrelated");
        skills(unrelated, skill("Python", 5));

        Page<CandidateResponse> page = projects.candidates(payroll, 1, FIRST_PAGE);
        assertThat(page.getContent()).extracting(c -> c.employee().name())
                .containsExactly("Insider", "Expert", "Partial");
        assertThat(page.getTotalElements()).isEqualTo(3);

        CandidateResponse first = page.getContent().get(0);
        assertThat(first.matchPercent()).isEqualTo(100);
        assertThat(first.inOwningDepartment()).isTrue();
        assertThat(first.freeCapacityPercent()).isEqualTo(50);

        CandidateResponse last = page.getContent().get(2);
        assertThat(last.matchPercent()).isEqualTo(25);
        assertThat(last.inOwningDepartment()).isFalse();
        assertThat(last.skills()).containsExactly(
                new SkillMatch("Java", 4, 2, false),
                new SkillMatch("Kafka", 3, null, false));

        // Needing 60% free leaves out the half-booked insider.
        assertThat(projects.candidates(payroll, 60, FIRST_PAGE).getContent()).extracting(c -> c.employee().name())
                .containsExactly("Expert", "Partial");

        // Paging keeps the order.
        assertThat(projects.candidates(payroll, 1, PageRequest.of(1, 2)).getContent())
                .extracting(c -> c.employee().name()).containsExactly("Partial");
    }

    @Test
    void onlyOpenProjectsWithRequiredSkillsHaveCandidates() {
        String dev = hire("DEV", "Dev Kumar");
        skills(dev, skill("Java", 4));

        String noSkills = project("EMPTY", ProjectStatus.PLANNED, null);
        assertThat(projects.candidates(noSkills, 1, FIRST_PAGE).getContent()).isEmpty();

        String done = project("DONE", ProjectStatus.COMPLETED, null);
        projects.replaceRequiredSkills(done, List.of(skill("Java", 3)));
        assertThatThrownBy(() -> projects.candidates(done, 1, FIRST_PAGE)).isInstanceOf(ConflictException.class);
    }

    private static List<String> names(Page<EmployeeResponse> page) {
        return page.getContent().stream().map(EmployeeResponse::name).toList();
    }
}
