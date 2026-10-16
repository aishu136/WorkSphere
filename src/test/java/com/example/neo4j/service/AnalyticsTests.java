package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.DepartmentSkillGapResponse;
import com.example.neo4j.dto.DepartmentSkillGapResponse.SkillSupply;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ManagerSpanResponse;
import com.example.neo4j.dto.OrgOverviewResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse.SkillCoverage;
import com.example.neo4j.dto.SkillRequest;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.repository.AnalyticsQueries;
import com.example.neo4j.support.ServiceTest;
import com.example.neo4j.support.SharedNeo4jTest;

/** Org analytics against an embedded Neo4j. */
@ServiceTest
class AnalyticsTests extends SharedNeo4jTest {

    @Autowired
    AnalyticsQueries analyticsQueries;

    // A low "too many reports" threshold, so a small test org has a WIDE manager.
    private AnalyticsService analytics;

    @Autowired
    EmployeeService employees;

    @Autowired
    DepartmentService departments;

    @Autowired
    ProjectService projects;

    private String hire(String code, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, managerId)).id();
    }

    private void skills(String employeeId, String... namesAndLevels) {
        for (int i = 0; i < namesAndLevels.length; i += 2) {
            employees.addSkill(employeeId, new SkillRequest(namesAndLevels[i], Integer.valueOf(namesAndLevels[i + 1])));
        }
    }

    private String project(String code, ProjectStatus status, String departmentId, SkillRequest... required) {
        String id = projects.create(new CreateProjectRequest(code, "Project " + code, null, ProjectStatus.ACTIVE,
                null, null, departmentId, null)).id();
        projects.replaceRequiredSkills(id, List.of(required));
        if (status != ProjectStatus.ACTIVE) {
            projects.changeStatus(id, status);
        }
        return id;
    }

    // Cleo <- Mia <- Dev1, Dev2, Dev3 (on leave); Cleo <- Max <- Solo. Lone has no manager.
    private String cleo, mia, max, dev1, dev2, dev3, solo, engineering, platform;

    private void organisation() {
        analytics = new AnalyticsService(analyticsQueries, 2);
        cleo = hire("CLEO", null);
        mia = hire("MIA", cleo);
        max = hire("MAX", cleo);
        dev1 = hire("DEV1", mia);
        dev2 = hire("DEV2", mia);
        dev3 = hire("DEV3", mia);
        solo = hire("SOLO", max);
        hire("LONE", null);
        employees.changeStatus(dev3, EmploymentStatus.ON_LEAVE);

        // Someone who left: no longer counted anywhere.
        employees.terminate(hire("GONE", max));

        engineering = departments.create(new CreateDepartmentRequest("ENG", "Engineering", null, null)).id();
        platform = departments.create(new CreateDepartmentRequest("PLAT", "Platform", null, engineering)).id();
        employees.assignDepartment(mia, engineering);
        employees.assignDepartment(dev1, engineering);
        employees.assignDepartment(dev2, platform);
    }

    @Test
    void theOverviewDescribesTheShapeOfTheOrganisation() {
        organisation();

        assertThat(analytics.overview()).isEqualTo(new OrgOverviewResponse(
                8,      // headcount, not counting GONE
                1,      // DEV3 on leave
                3,      // Cleo, Mia, Max have reports
                2.0,    // (2 + 3 + 1) / 3
                3,      // Mia
                3,      // Dev -> Mia -> Cleo
                2,      // Cleo and Lone have no manager
                5));    // only Mia, Dev1 and Dev2 are in a department
    }

    @Test
    void managersAreListedWidestFirstAndFlagged() {
        organisation();

        List<ManagerSpanResponse> managers = analytics.managers(PageRequest.of(0, 20)).getContent();
        assertThat(managers).extracting(m -> m.manager().name())
                .containsExactly("Name MIA", "Name CLEO", "Name MAX");

        ManagerSpanResponse widest = managers.get(0);
        assertThat(widest.directReports()).isEqualTo(3);
        assertThat(widest.flag()).isEqualTo("WIDE");
        ManagerSpanResponse top = managers.get(1);
        assertThat(top.totalReports()).isEqualTo(6);
        assertThat(top.layersBelow()).isEqualTo(2);
        assertThat(top.flag()).isNull();
        assertThat(managers.get(2).flag()).isEqualTo("NARROW");
    }

    @Test
    void requiredSkillsCoveredByOnePersonOrNobodyAreAtRisk() {
        organisation();
        skills(dev1, "Java", "4", "Kafka", "3");
        skills(dev2, "Java", "3");
        skills(dev3, "Kafka", "5");

        String payroll = project("PAYROLL", ProjectStatus.ACTIVE, engineering,
                new SkillRequest("Java", 3), new SkillRequest("Kafka", 3));
        projects.assign(payroll, dev1, new AssignmentRequest("Dev", 50, null, null));
        projects.assign(payroll, dev2, new AssignmentRequest("Dev", 50, null, null));
        // Dev3's assignment has ended, so their Kafka doesn't count.
        projects.assign(payroll, dev3, new AssignmentRequest("Dev", 50, LocalDate.now().minusMonths(2),
                LocalDate.now().minusDays(1)));

        String safe = project("SAFE", ProjectStatus.ACTIVE, engineering, new SkillRequest("Java", 3));
        projects.assign(safe, dev1, new AssignmentRequest("Dev", 25, null, null));
        projects.assign(safe, dev2, new AssignmentRequest("Dev", 25, null, null));

        project("NEW", ProjectStatus.PLANNED, null, new SkillRequest("Go", 2));
        project("DONE", ProjectStatus.COMPLETED, null, new SkillRequest("Go", 2));

        List<ProjectSkillRiskResponse> risks = analytics.projectSkillRisks(true);
        assertThat(risks).extracting(r -> r.project().code()).containsExactly("NEW", "PAYROLL");

        ProjectSkillRiskResponse payrollRisk = risks.get(1);
        assertThat(payrollRisk.skillsAtRisk()).isEqualTo(1);
        SkillCoverage java = payrollRisk.skills().get(0);
        assertThat(java.skill()).isEqualTo("Java");
        assertThat(java.coveredBy()).extracting(EmployeeSummary::name).containsExactlyInAnyOrder("Name DEV1", "Name DEV2");
        assertThat(java.atRisk()).isFalse();
        SkillCoverage kafka = payrollRisk.skills().get(1);
        assertThat(kafka.coveredBy()).extracting(EmployeeSummary::name).containsExactly("Name DEV1");
        assertThat(kafka.atRisk()).isTrue();

        assertThat(analytics.projectSkillRisks(false)).extracting(r -> r.project().code())
                .containsExactly("NEW", "PAYROLL", "SAFE");
    }

    @Test
    void departmentsAreShortWhenTooFewQualifiedPeopleCanCoverTheirProjects() {
        organisation();
        skills(dev1, "Java", "4");
        skills(dev2, "Java", "5");    // in Platform, a sub-department: counts for Engineering
        skills(dev3, "Rust", "5");    // on leave: doesn't count
        skills(solo, "Rust", "5");    // not in Engineering

        project("PAYROLL", ProjectStatus.ACTIVE, engineering, new SkillRequest("Java", 3));
        project("SAFE", ProjectStatus.ACTIVE, engineering, new SkillRequest("Java", 4), new SkillRequest("Rust", 3));
        project("DONE", ProjectStatus.COMPLETED, engineering, new SkillRequest("Cobol", 1));

        List<DepartmentSkillGapResponse> gaps = analytics.skillGaps(null);
        assertThat(gaps).hasSize(1);
        DepartmentSkillGapResponse eng = gaps.get(0);
        assertThat(eng.department().code()).isEqualTo("ENG");
        assertThat(eng.skillsShort()).isEqualTo(1);
        // Java: two projects, the higher level (4) counts, Dev1 and Dev2 qualify. Rust: nobody qualifies.
        assertThat(eng.skills()).containsExactly(
                new SkillSupply("Java", 4, 2, 2, false),
                new SkillSupply("Rust", 3, 1, 0, true));

        assertThat(analytics.skillGaps(platform)).isEmpty();
    }
}
