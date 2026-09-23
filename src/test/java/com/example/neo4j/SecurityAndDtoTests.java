package com.example.neo4j;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditController;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.controller.AuthController;
import com.example.neo4j.controller.DepartmentController;
import com.example.neo4j.controller.EmployeeController;
import com.example.neo4j.controller.OfficeController;
import com.example.neo4j.controller.ProjectController;
import com.example.neo4j.controller.UserController;
import com.example.neo4j.dto.CreateEmployeeRequest;
import com.example.neo4j.dto.DepartmentSummary;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.exception.ConflictException;
import com.example.neo4j.repository.EmployeeQueries;
import com.example.neo4j.security.SecurityConfig;
import com.example.neo4j.security.TeamAuthorization;
import com.example.neo4j.security.TokenService;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.OfficeService;
import com.example.neo4j.service.ProjectService;
import com.example.neo4j.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = {EmployeeController.class, DepartmentController.class, AuthController.class,
        UserController.class, AuditController.class, OfficeController.class, ProjectController.class})
@Import({SecurityConfig.class, TokenService.class, TeamAuthorization.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-32-bytes-long",
        "app.cors.allowed-origins=http://localhost:4200"
})
class SecurityAndDtoTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoBean
    EmployeeService employeeService;

    @MockitoBean
    DepartmentService departmentService;

    @MockitoBean
    OfficeService officeService;

    @MockitoBean
    ProjectService projectService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    AuditLog auditLog;

    // Backs the manager team check in TeamAuthorization.
    @MockitoBean
    EmployeeQueries employeeQueries;

    private static final String VALID_EMPLOYEE = """
            {"employeeCode":"E100","name":"Asha","email":"asha@example.com","hireDate":"2024-01-15"}
            """;

    private static SimpleGrantedAuthority role(String name) {
        return new SimpleGrantedAuthority("ROLE_" + name);
    }

    // A plain EMPLOYEE login called "mgr"; manager rights come only from the org chart.
    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return jwt().jwt(token -> token.subject("mgr")).authorities(role("EMPLOYEE"));
    }

    private static EmployeeResponse employee(String id, String name) {
        return new EmployeeResponse(id, "E-" + id, name, id + "@example.com", "Engineer",
                LocalDate.of(2024, 1, 15), EmploymentStatus.ACTIVE, null,
                new DepartmentSummary("d1", "ENG", "Engineering"),
                new EmployeeSummary("m1", "Manager", "Director"), null, null, List.of(), 0, 0);
    }

    // ---- Authentication and roles -----------------------------------------------

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mvc.perform(get("/employees")).andExpect(status().isUnauthorized());
        mvc.perform(get("/departments")).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCanReadButNotWrite() throws Exception {
        when(employeeService.search(any(EmployeeFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/employees").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isOk());

        mvc.perform(post("/employees").with(jwt().authorities(role("EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EMPLOYEE))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/employees/e1").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isForbidden());

        mvc.perform(post("/departments").with(jwt().authorities(role("EMPLOYEE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ENG\",\"name\":\"Engineering\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCannotManageUsers() throws Exception {
        mvc.perform(get("/users").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginIssuesTokenThatWorksOnProtectedEndpoint() throws Exception {
        when(userDetailsService.loadUserByUsername(eq("hr.user"))).thenReturn(
                User.withUsername("hr.user")
                        .password(passwordEncoder.encode("correct-password"))
                        .roles("HR")
                        .build());

        String body = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr.user\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(body).get("accessToken").asText();

        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("hr.user"))
                .andExpect(jsonPath("$.roles", hasItem("HR")));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        when(userDetailsService.loadUserByUsername(eq("hr.user"))).thenReturn(
                User.withUsername("hr.user")
                        .password(passwordEncoder.encode("correct-password"))
                        .roles("HR")
                        .build());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr.user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("correct-password"))));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        mvc.perform(get("/employees").header("Authorization", "Bearer not.a.valid-token"))
                .andExpect(status().isUnauthorized());
    }

    // ---- Employees --------------------------------------------------------------

    @Test
    void hrCanCreateEmployee() throws Exception {
        when(employeeService.create(any(CreateEmployeeRequest.class))).thenReturn(employee("new-id", "Asha"));

        mvc.perform(post("/employees").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EMPLOYEE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("new-id"))
                .andExpect(jsonPath("$.department.code").value("ENG"))
                .andExpect(jsonPath("$.manager.name").value("Manager"))
                .andExpect(jsonPath("$.hireDate").value("2024-01-15"));
    }

    @Test
    void invalidEmployeeIsRejected() throws Exception {
        mvc.perform(post("/employees").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeCode\":\"x\",\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.employeeCode").exists())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.hireDate").exists());
    }

    @Test
    void directoryFiltersAndPagingAreBound() throws Exception {
        EmployeeFilter expected = new EmployeeFilter("pri", EmploymentStatus.ACTIVE, "d1", "java", "acme", "o1", 50);
        when(employeeService.search(expected, PageRequest.of(1, 2)))
                .thenReturn(new PageImpl<>(List.of(employee("a", "Priya")), PageRequest.of(1, 2), 3));

        mvc.perform(get("/employees")
                        .param("name", "pri").param("status", "ACTIVE").param("departmentId", "d1")
                        .param("skill", "java").param("company", "acme")
                        .param("officeId", "o1").param("maxAllocation", "50")
                        .param("page", "1").param("size", "2")
                        .with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Priya"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(true));

        verify(employeeService).search(expected, PageRequest.of(1, 2));
    }

    @Test
    void unknownStatusIsBadRequest() throws Exception {
        mvc.perform(get("/employees").param("status", "RETIRED").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/employees/e1/status").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportingCycleIsConflict() throws Exception {
        when(employeeService.assignManager("ceo", "dev")).thenThrow(new ConflictException("Reporting cycle"));

        mvc.perform(put("/employees/ceo/manager/dev").with(jwt().authorities(role("HR"))))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("cycle")));
    }

    @Test
    void outOfRangePagingIsRejected() throws Exception {
        mvc.perform(get("/employees").param("size", "101").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.size").exists());

        mvc.perform(get("/employees").param("page", "-1").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/departments").param("page", "abc").with(jwt().authorities(role("EMPLOYEE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankSkillInsideListIsRejected() throws Exception {
        mvc.perform(put("/employees/e1/skills").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"Java\"},{\"name\":\"\"}]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/employees").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ---- Manager self-service ---------------------------------------------------

    @Test
    void managerCanChangeStatusAndSkillsOnlyForTheirTeam() throws Exception {
        when(employeeQueries.isInTeamOf("mgr", "report", false)).thenReturn(true);
        when(employeeService.changeStatus("report", EmploymentStatus.ON_LEAVE)).thenReturn(employee("report", "R"));

        mvc.perform(put("/employees/report/status").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/employees/report/skills").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kafka\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/employees/report/skills").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"name\":\"Kafka\"}]"))
                .andExpect(status().isOk());
        mvc.perform(delete("/employees/report/skills/s1").with(manager()))
                .andExpect(status().isOk());

        // Someone outside the team (the mock answers false for anything not stubbed).
        mvc.perform(put("/employees/stranger/status").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/employees/stranger/skills").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kafka\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotDoHrOnlyActionsEvenForTheirTeam() throws Exception {
        when(employeeQueries.isInTeamOf(eq("mgr"), any(), anyBoolean())).thenReturn(true);

        mvc.perform(put("/employees/report").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"email\":\"x@example.com\",\"hireDate\":\"2024-01-01\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/employees/report").with(manager())).andExpect(status().isForbidden());
        mvc.perform(delete("/employees/report/manager").with(manager())).andExpect(status().isForbidden());
        mvc.perform(put("/employees/report/department/d1").with(manager())).andExpect(status().isForbidden());
        mvc.perform(post("/employees").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_EMPLOYEE))
                .andExpect(status().isForbidden());
        mvc.perform(put("/departments/d1/head/report").with(manager())).andExpect(status().isForbidden());
    }

    @Test
    void managerCanOnlyMoveReportsWithinTheirTeam() throws Exception {
        when(employeeQueries.isInTeamOf("mgr", "report", false)).thenReturn(true);
        when(employeeQueries.isInTeamOf("mgr", "teamLead", true)).thenReturn(true);
        when(employeeService.assignManager("report", "teamLead")).thenReturn(employee("report", "R"));

        mvc.perform(put("/employees/report/manager/teamLead").with(manager()))
                .andExpect(status().isOk());

        // New manager outside the team.
        mvc.perform(put("/employees/report/manager/otherTeamLead").with(manager()))
                .andExpect(status().isForbidden());

        // Target outside the team, even if the new manager is inside it.
        mvc.perform(put("/employees/stranger/manager/teamLead").with(manager()))
                .andExpect(status().isForbidden());
    }

    @Test
    void hrDoesNotNeedTheTeamCheck() throws Exception {
        when(employeeService.changeStatus("anyone", EmploymentStatus.ON_LEAVE)).thenReturn(employee("anyone", "A"));

        mvc.perform(put("/employees/anyone/status").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
                .andExpect(status().isOk());

        verify(employeeQueries, org.mockito.Mockito.never()).isInTeamOf(any(), any(), anyBoolean());
    }

    @Test
    void anonymousManagerEndpointIsUnauthorized() throws Exception {
        mvc.perform(put("/employees/report/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meEndpointsExposeTheLinkedEmployee() throws Exception {
        when(userService.findEmployeeId("mgr")).thenReturn(java.util.Optional.of("e-mgr"));
        when(employeeService.findById("e-mgr")).thenReturn(employee("e-mgr", "Maya Manager"));

        mvc.perform(get("/auth/me").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("e-mgr"));
        mvc.perform(get("/employees/me").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Maya Manager"));

        // Unlinked login.
        mvc.perform(get("/employees/me").with(jwt().jwt(token -> token.subject("nobody"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminCanLinkLogins() throws Exception {
        mvc.perform(put("/users/u1/employee/e1").with(jwt().authorities(role("HR"))))
                .andExpect(status().isForbidden());
    }

    // ---- Audit trail ------------------------------------------------------------

    @Test
    void fullAuditLogIsAdminOnly() throws Exception {
        when(auditLog.search(any(AuditFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/audit").with(jwt().authorities(role("ADMIN")))).andExpect(status().isOk());
        mvc.perform(get("/audit").with(jwt().authorities(role("HR")))).andExpect(status().isForbidden());
        mvc.perform(get("/audit").with(manager())).andExpect(status().isForbidden());
    }

    @Test
    void recordHistoryIsHrOrAdmin() throws Exception {
        when(auditLog.search(any(AuditFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/employees/e1/history").with(jwt().authorities(role("HR")))).andExpect(status().isOk());
        mvc.perform(get("/departments/d1/history").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isOk());

        // Managers and regular employees cannot read history, even for their own team.
        when(employeeQueries.isInTeamOf(eq("mgr"), any(), anyBoolean())).thenReturn(true);
        mvc.perform(get("/employees/e1/history").with(manager())).andExpect(status().isForbidden());

        verify(auditLog).search(AuditFilter.forTarget(AuditTargetType.EMPLOYEE, "e1"), PageRequest.of(0, 20));
    }

    @Test
    void auditFiltersAreBound() throws Exception {
        AuditFilter expected = new AuditFilter("priya", AuditAction.EMPLOYEE_UPDATED, AuditTargetType.EMPLOYEE, "e1",
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        when(auditLog.search(expected, PageRequest.of(0, 20))).thenReturn(Page.empty());

        mvc.perform(get("/audit").with(jwt().authorities(role("ADMIN")))
                        .param("actor", "priya").param("action", "EMPLOYEE_UPDATED")
                        .param("targetType", "EMPLOYEE").param("targetId", "e1")
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-02-01T00:00:00Z"))
                .andExpect(status().isOk());

        verify(auditLog).search(expected, PageRequest.of(0, 20));

        mvc.perform(get("/audit").param("action", "NOT_AN_ACTION").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/audit").param("from", "yesterday").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginAttemptsAreAudited() throws Exception {
        // A fresh User per lookup, like the real service: Spring erases the password from the
        // returned object after a successful login.
        when(userDetailsService.loadUserByUsername(eq("hr.user"))).thenAnswer(invocation ->
                User.withUsername("hr.user")
                        .password(passwordEncoder.encode("correct-password"))
                        .roles("HR")
                        .build());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr.user\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk());
        verify(auditLog).recordAs("hr.user", AuditAction.LOGIN_SUCCEEDED, AuditTargetType.USER, "hr.user",
                java.util.Map.of());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr.user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        verify(auditLog).recordAs("hr.user", AuditAction.LOGIN_FAILED, AuditTargetType.USER, "hr.user",
                java.util.Map.of("reason", "BAD_CREDENTIALS"));

        User disabled = (User) User.withUsername("gone.user")
                .password(passwordEncoder.encode("correct-password")).roles("EMPLOYEE").disabled(true).build();
        when(userDetailsService.loadUserByUsername(eq("gone.user"))).thenReturn(disabled);

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"gone.user\",\"password\":\"correct-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid username or password"));
        verify(auditLog).recordAs("gone.user", AuditAction.LOGIN_FAILED, AuditTargetType.USER, "gone.user",
                java.util.Map.of("reason", "ACCOUNT_DISABLED"));
    }

    // ---- Offices and projects ---------------------------------------------------

    @Test
    void officesAndProjectsAreReadableByAllButChangedOnlyByHr() throws Exception {
        when(officeService.search(any(), any(Pageable.class))).thenReturn(Page.empty());
        when(projectService.search(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/offices").with(manager())).andExpect(status().isOk());
        mvc.perform(get("/projects").with(manager())).andExpect(status().isOk());

        // Even a manager with a team can't change offices or staff projects.
        when(employeeQueries.isInTeamOf(eq("mgr"), any(), anyBoolean())).thenReturn(true);
        mvc.perform(post("/offices").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BLR-01\",\"name\":\"Bengaluru\",\"city\":\"Bengaluru\",\"country\":\"India\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/projects/p1/members/e1").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationPercent\":50}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/employees/e1/office/o1").with(manager())).andExpect(status().isForbidden());

        when(officeService.create(any())).thenReturn(new com.example.neo4j.dto.OfficeResponse(
                "o1", "BLR-01", "Bengaluru", "Bengaluru", "India", null, null, 0));
        mvc.perform(post("/offices").with(jwt().authorities(role("HR"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BLR-01\",\"name\":\"Bengaluru\",\"city\":\"Bengaluru\",\"country\":\"India\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BLR-01"));
    }

    @Test
    void allocationMustBeBetween1And100() throws Exception {
        for (String body : List.of("{\"allocationPercent\":0}", "{\"allocationPercent\":101}", "{}")) {
            mvc.perform(put("/projects/p1/members/e1").with(jwt().authorities(role("HR")))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.allocationPercent").exists());
        }

        mvc.perform(get("/employees").param("maxAllocation", "101").with(manager()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/projects").param("status", "FINISHED").with(manager()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void officeAndProjectHistoryIsHrOrAdmin() throws Exception {
        when(auditLog.search(any(AuditFilter.class), any(Pageable.class))).thenReturn(Page.empty());

        mvc.perform(get("/projects/p1/history").with(jwt().authorities(role("HR")))).andExpect(status().isOk());
        mvc.perform(get("/offices/o1/history").with(jwt().authorities(role("ADMIN")))).andExpect(status().isOk());
        mvc.perform(get("/projects/p1/history").with(manager())).andExpect(status().isForbidden());

        verify(auditLog).search(AuditFilter.forTarget(AuditTargetType.PROJECT, "p1"), PageRequest.of(0, 20));
    }

    // ---- Departments ------------------------------------------------------------

    @Test
    void departmentValidationAndDelete() throws Exception {
        mvc.perform(post("/departments").with(jwt().authorities(role("HR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"has space\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.name").exists());

        mvc.perform(delete("/departments/d1").with(jwt().authorities(role("ADMIN"))))
                .andExpect(status().isNoContent());
        verify(departmentService).delete("d1");
    }
}
