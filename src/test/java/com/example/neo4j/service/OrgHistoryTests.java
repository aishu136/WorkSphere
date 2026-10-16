package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;

import com.example.neo4j.dto.CreateDepartmentRequest;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.HistoryPeriod;
import com.example.neo4j.dto.ReportingHistoryResponse;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.support.ServiceTest;
import com.example.neo4j.support.SharedNeo4jTest;

/**
 * The org chart over time, rebuilt from the audit trail, against an embedded Neo4j.
 *
 */
@ServiceTest
class OrgHistoryTests extends SharedNeo4jTest {

    @Autowired
    OrgHistoryService history;

    @Autowired
    EmployeeService employees;

    @Autowired
    DepartmentService departments;

    @Autowired
    Neo4jClient client;

    private String hire(String code, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, "Name " + code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, managerId)).id();
    }

    private static OffsetDateTime at(LocalDate day) {
        return day.atTime(9, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    // Records an event as the audit trail would have on that day. seq orders one employee's events.
    private void event(String employeeId, String action, LocalDate day, long seq, String detailsJson) {
        client.query("""
                        CREATE (:AuditEvent {id: randomUUID(), seq: $seq, timestamp: $timestamp, actor: 'hr',
                                             action: $action, targetType: 'EMPLOYEE', targetId: $id, details: $details})
                        """)
                .bindAll(Map.of("seq", seq, "timestamp", at(day), "action", action, "id", employeeId,
                        "details", detailsJson))
                .run();
    }

    private void reportsTo(String employeeId, String managerId) {
        client.query("MATCH (e:Employee {id: $id}), (m:Employee {id: $m}) CREATE (e)-[:REPORTS_TO]->(m)")
                .bindAll(Map.of("id", employeeId, "m", managerId)).run();
    }

    // An employee who has been here since 2025, with their history in the audit trail.
    private String longStanding(String code) {
        String id = "id-" + code;
        client.query("""
                        CREATE (:Employee {id: $id, employeeCode: $code, name: $name, searchName: toLower($name),
                                           email: $email, status: 'ACTIVE'})
                        """)
                .bindAll(Map.of("id", id, "code", code, "name", "Name " + code, "email", code + "@example.com"))
                .run();
        return id;
    }

    @Test
    void changesMadeThroughTheApiBuildTheHistory() {
        String mia = hire("MIA", null);
        String max = hire("MAX", null);
        String dev = hire("DEV", mia);
        String eng = departments.create(new CreateDepartmentRequest("ENG", "Engineering", null, null)).id();
        employees.assignDepartment(dev, eng);
        employees.assignManager(dev, max);

        ReportingHistoryResponse result = history.history(dev);
        assertThat(result.managers()).extracting(HistoryPeriod::id).containsExactly(mia, max);
        assertThat(result.managers()).extracting(HistoryPeriod::name).containsExactly("Name MIA", "Name MAX");
        HistoryPeriod first = result.managers().get(0);
        assertThat(first.from()).isNotNull();
        assertThat(first.to()).isEqualTo(result.managers().get(1).from());
        assertThat(result.managers().get(1).to()).isNull();
        assertThat(result.departments()).extracting(HistoryPeriod::name).containsExactly("Engineering");

        // Termination ends the last period.
        employees.terminate(dev);
        assertThat(history.history(dev).managers().get(1).to()).isNotNull();
        assertThat(history.history(dev).departments().get(0).to()).isNotNull();

        assertThat(history.reportsOn(max, LocalDate.now().minusDays(1))).isEmpty();
        assertThatThrownBy(() -> history.history("nobody")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void whoReportedToWhomOnAPastDate() {
        String mia = hire("MIA", null);
        String max = hire("MAX", null);

        // Olga joined under Mia on 1 January 2025 and moved to Max on 1 June 2025.
        String olga = longStanding("OLGA");
        reportsTo(olga, max);
        event(olga, "EMPLOYEE_CREATED", LocalDate.of(2025, 1, 1), 1, "{}");
        event(olga, "EMPLOYEE_MANAGER_CHANGED", LocalDate.of(2025, 1, 1), 2,
                "{\"managerId\":{\"from\":null,\"to\":\"" + mia + "\"}}");
        event(olga, "EMPLOYEE_MANAGER_CHANGED", LocalDate.of(2025, 6, 1), 3,
                "{\"managerId\":{\"from\":\"" + mia + "\",\"to\":\"" + max + "\"}}");

        assertThat(names(history.reportsOn(mia, LocalDate.of(2025, 3, 15)))).containsExactly("Name OLGA");
        assertThat(history.reportsOn(max, LocalDate.of(2025, 3, 15))).isEmpty();
        assertThat(names(history.reportsOn(max, LocalDate.of(2025, 7, 1)))).containsExactly("Name OLGA");
        // On the day of the move, the end of the day counts.
        assertThat(names(history.reportsOn(max, LocalDate.of(2025, 6, 1)))).containsExactly("Name OLGA");
        // Before she joined.
        assertThat(history.reportsOn(mia, LocalDate.of(2024, 12, 31))).isEmpty();

        List<HistoryPeriod> managers = history.history(olga).managers();
        assertThat(managers).extracting(HistoryPeriod::id).containsExactly(mia, max);
        assertThat(managers.get(0).from()).isEqualTo(at(LocalDate.of(2025, 1, 1)));
        assertThat(managers.get(0).to()).isEqualTo(at(LocalDate.of(2025, 6, 1)));
    }

    @Test
    void linksFromBeforeTheAuditTrailStartAtAnUnknownTime() {
        String mia = hire("MIA", null);
        String old = longStanding("OLD");
        reportsTo(old, mia);

        assertThat(history.history(old).managers()).containsExactly(new HistoryPeriod(mia, "Name MIA", null, null));
        assertThat(names(history.reportsOn(mia, LocalDate.of(2020, 1, 1)))).containsExactly("Name OLD");
    }

    private static List<String> names(List<EmployeeSummary> people) {
        return people.stream().map(EmployeeSummary::name).toList();
    }
}
