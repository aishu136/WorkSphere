package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

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

import com.example.neo4j.dto.AssignmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateProjectRequest;
import com.example.neo4j.dto.EmployeeAssignmentResponse;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.ProjectMemberResponse;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.repository.LeaveQueries;
import com.example.neo4j.repository.ProjectQueries;
import com.example.neo4j.support.EmbeddedNeo4j;

import ac.simons.neo4j.migrations.springframework.boot.autoconfigure.MigrationsAutoConfiguration;

/**
 * Time-bound allocations against an embedded Neo4j: the 100% rule holds on every day, not in
 * total. Dates are in 2030 so they don't depend on today. Each test runs in a transaction that is
 * rolled back.
 */
@DataNeo4jTest
@ImportAutoConfiguration(MigrationsAutoConfiguration.class)
@Import({EmployeeService.class, ProjectService.class, EmployeeQueries.class, LeaveQueries.class,
        ProjectQueries.class, com.example.neo4j.audit.AuditLog.class})
class AllocationOverTimeTests {

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
    ProjectService projects;

    private static LocalDate day(int month, int dayOfMonth) {
        return LocalDate.of(2030, month, dayOfMonth);
    }

    private String hire(String code) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, null)).id();
    }

    private String project(String code, LocalDate start, LocalDate end) {
        return projects.create(new CreateProjectRequest(code, "Project " + code, null, ProjectStatus.ACTIVE,
                start, end, null, null)).id();
    }

    private ProjectMemberResponse assign(String projectId, String employeeId, int allocation, LocalDate start,
                                         LocalDate end) {
        return projects.assign(projectId, employeeId, new AssignmentRequest("Dev", allocation, start, end));
    }

    @Test
    void peopleCanBeFullyBookedOneAfterAnother() {
        String dev = hire("DEV");
        String q1 = project("Q1", null, null);
        String q2 = project("Q2", null, null);
        String overlap = project("OVERLAP", null, null);

        assign(q1, dev, 100, day(1, 1), day(3, 31));
        assign(q2, dev, 100, day(4, 1), day(6, 30));

        // Mid-March to mid-April overlaps the first booking.
        assertThatThrownBy(() -> assign(overlap, dev, 10, day(3, 15), day(4, 15)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Name DEV is already 100% allocated on 2030-03-15; adding 10% would exceed 100%");

        // Open-ended from July is free.
        assertThat(assign(overlap, dev, 100, day(7, 1), null).endDate()).isNull();

        // Moving an assignment's dates is checked too.
        assertThatThrownBy(() -> assign(q2, dev, 100, day(4, 1), day(7, 1))).isInstanceOf(ConflictException.class);
    }

    @Test
    void datesDefaultToTheProjectsAndAreKeptOnUpdate() {
        String dev = hire("DEV");
        String dated = project("DATED", day(2, 1), day(5, 31));
        String open = project("OPEN", null, null);

        ProjectMemberResponse member = assign(dated, dev, 50, null, null);
        assertThat(member.startDate()).isEqualTo(day(2, 1));
        assertThat(member.endDate()).isEqualTo(day(5, 31));
        assertThat(member.since()).isEqualTo(LocalDate.now());

        // A project without dates starts today and is open-ended.
        ProjectMemberResponse openEnded = assign(open, dev, 50, null, null);
        assertThat(openEnded.startDate()).isEqualTo(LocalDate.now());
        assertThat(openEnded.endDate()).isNull();

        // Changing only the allocation keeps the dates; a new end date replaces the old one.
        assertThat(assign(dated, dev, 40, null, null).startDate()).isEqualTo(day(2, 1));
        assertThat(assign(dated, dev, 40, null, day(3, 31)).endDate()).isEqualTo(day(3, 31));

        assertThatThrownBy(() -> assign(dated, dev, 40, day(4, 1), day(3, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentAllocationOnlyCountsAssignmentsRunningToday() {
        String dev = hire("DEV");
        String later = project("LATER", null, null);
        String now = project("NOW", null, null);
        String ended = project("ENDED", null, null);

        assign(later, dev, 70, day(1, 1), null);
        assign(now, dev, 20, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10));
        assign(ended, dev, 30, LocalDate.now().minusYears(1), LocalDate.now().minusDays(1));

        EmployeeResponse profile = employees.findById(dev);
        assertThat(profile.allocationPercent()).isEqualTo(20);
        assertThat(projects.projectsOf(dev).allocationPercent()).isEqualTo(20);
        assertThat(projects.projectsOf(dev).assignments()).extracting(EmployeeAssignmentResponse::startDate)
                .contains(day(1, 1));

        // The directory's availability filter is about today too.
        assertThat(employees.search(new EmployeeFilter(null, null, null, null, null, null, null, 20),
                PageRequest.of(0, 20)).getContent()).extracting(EmployeeResponse::id).contains(dev);
    }

    @Test
    void reopeningAProjectChecksEachMembersOwnDates() {
        String dev = hire("DEV");
        String old = project("OLD", null, null);
        String spring = project("SPRING", null, null);
        String winter = project("WINTER", null, null);

        assign(old, dev, 60, day(1, 1), day(3, 31));
        projects.changeStatus(old, ProjectStatus.COMPLETED);

        // Booked again after OLD's dates: reopening is fine.
        assign(spring, dev, 70, day(4, 1), day(6, 30));
        projects.changeStatus(old, ProjectStatus.ACTIVE);
        projects.changeStatus(old, ProjectStatus.COMPLETED);

        // Booked during OLD's dates: reopening would mean 130% in February.
        assign(winter, dev, 70, day(2, 1), day(2, 28));
        assertThatThrownBy(() -> projects.changeStatus(old, ProjectStatus.ACTIVE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Name DEV");
    }
}
