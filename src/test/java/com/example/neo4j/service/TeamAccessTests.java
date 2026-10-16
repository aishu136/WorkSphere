package com.example.neo4j.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.CreateUserRequest;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.security.Role;
import com.example.neo4j.support.ServiceTest;
import com.example.neo4j.support.SharedNeo4jTest;

/**
 * The org-chart team check and login linking, against an embedded Neo4j. Org used in each test:
 *
 *            ceo
 *          /     \
 *       lead    otherLead
 *        |          |
 *       dev      stranger
 */
@ServiceTest
class TeamAccessTests extends SharedNeo4jTest {

    @Autowired
    UserService users;

    @Autowired
    EmployeeService employees;

    @Autowired
    EmployeeQueries employeeQueries;

    @Autowired
    AppUserRepository userRepository;

    private String ceo, lead, dev, otherLead, stranger;

    private String hire(String code, String managerId) {
        return employees.create(new CreateEmployeeRequest(code, code, code.toLowerCase() + "@example.com",
                null, LocalDate.of(2024, 1, 1), null, managerId)).id();
    }

    private AppUser login(String username, String employeeId) {
        return users.create(new CreateUserRequest(username, "a-long-enough-password", Set.of(Role.EMPLOYEE),
                employeeId));
    }

    private void buildOrg() {
        ceo = hire("CEO", null);
        lead = hire("LEAD", ceo);
        dev = hire("DEV", lead);
        otherLead = hire("OTHER", ceo);
        stranger = hire("STRANGER", otherLead);
    }

    @Test
    void teamIsEveryoneBelowYouInTheReportingLine() {
        buildOrg();
        login("lead.user", lead);
        login("ceo.user", ceo);

        assertThat(employeeQueries.isInTeamOf("lead.user", dev, false)).isTrue();
        assertThat(employeeQueries.isInTeamOf("lead.user", stranger, false)).isFalse();
        assertThat(employeeQueries.isInTeamOf("lead.user", otherLead, false)).isFalse();
        assertThat(employeeQueries.isInTeamOf("lead.user", ceo, false)).isFalse();

        // Not yourself, unless explicitly included (used for "move a report to me").
        assertThat(employeeQueries.isInTeamOf("lead.user", lead, false)).isFalse();
        assertThat(employeeQueries.isInTeamOf("lead.user", lead, true)).isTrue();

        // Any depth: the CEO manages the developer through the lead.
        assertThat(employeeQueries.isInTeamOf("ceo.user", dev, false)).isTrue();
        assertThat(employeeQueries.isInTeamOf("ceo.user", stranger, false)).isTrue();
    }

    @Test
    void rightsFollowTheOrgChartImmediately() {
        buildOrg();
        login("lead.user", lead);

        employees.assignManager(stranger, lead);
        assertThat(employeeQueries.isInTeamOf("lead.user", stranger, false)).isTrue();

        employees.assignManager(dev, otherLead);
        assertThat(employeeQueries.isInTeamOf("lead.user", dev, false)).isFalse();
    }

    @Test
    void unlinkedDisabledOrUnknownLoginsManageNobody() {
        buildOrg();
        AppUser unlinked = login("unlinked.user", null);

        assertThat(employeeQueries.isInTeamOf("unlinked.user", dev, false)).isFalse();
        assertThat(employeeQueries.isInTeamOf("no.such.user", dev, false)).isFalse();

        users.linkEmployee(unlinked.getId(), lead);
        assertThat(employeeQueries.isInTeamOf("unlinked.user", dev, false)).isTrue();

        AppUser user = userRepository.findByUsername("unlinked.user").orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);
        assertThat(employeeQueries.isInTeamOf("unlinked.user", dev, false)).isFalse();
    }

    @Test
    void anEmployeeCanHaveOnlyOneLoginAndMustStillWorkHere() {
        buildOrg();
        login("first", dev);

        assertThatThrownBy(() -> login("second", dev)).isInstanceOf(ConflictException.class);

        AppUser other = login("other", null);
        assertThatThrownBy(() -> users.linkEmployee(other.getId(), dev)).isInstanceOf(ConflictException.class);

        // Relinking the same login to the same employee is fine.
        AppUser first = userRepository.findByUsername("first").orElseThrow();
        assertThat(users.linkEmployee(first.getId(), dev).getEmployeeId()).isEqualTo(dev);

        assertThat(users.unlinkEmployee(first.getId()).getEmployeeId()).isNull();
        assertThat(users.findEmployeeId("first")).isEmpty();
    }

    @Test
    void terminationDisablesTheLoginAndEndsManagerRights() {
        buildOrg();
        login("dev.user", dev);

        assertThat(employeeQueries.isInTeamOf("dev.user", dev, true)).isTrue();

        employees.terminate(dev);

        assertThat(userRepository.findByUsername("dev.user").orElseThrow().isEnabled()).isFalse();
        assertThat(employeeQueries.isInTeamOf("dev.user", dev, true)).isFalse();

        AppUser another = login("another", null);
        assertThatThrownBy(() -> users.linkEmployee(another.getId(), dev)).isInstanceOf(ConflictException.class);
    }
}
