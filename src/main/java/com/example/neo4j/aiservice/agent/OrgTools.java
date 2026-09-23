package com.example.neo4j.aiservice.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.example.neo4j.dto.DepartmentResponse;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.EmployeeProjectsResponse;
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.OfficeService;
import com.example.neo4j.service.ProjectService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools the AI assistant can call. They are deliberately read-only and limited to data every
 * logged-in user can already read through the API (directory, org chart, departments, offices,
 * projects), so the assistant can never change anything or reveal more than the caller could
 * see anyway.
 * Audit history is intentionally not exposed.
 *
 * Results are capped so a single call can't flood the model's context.
 */
@Component
public class OrgTools {

    static final int MAX_RESULTS = 20;
    static final int MAX_LIST_RESULTS = 50;
    private static final int MAX_DEPARTMENTS_SCANNED = 100;

    private final EmployeeService employees;
    private final DepartmentService departments;
    private final OfficeService offices;
    private final ProjectService projects;

    public OrgTools(EmployeeService employees, DepartmentService departments, OfficeService offices,
                    ProjectService projects) {
        this.employees = employees;
        this.departments = departments;
        this.offices = offices;
        this.projects = projects;
    }

    @Tool("Search the employee directory. All filters are optional and combine with AND. "
            + "Returns matching employees with their id, job title, department, office, manager, status "
            + "and current project allocation. Use maxAllocationPercent to find people with free capacity.")
    public Map<String, Object> searchEmployees(
            @P(value = "Start of the employee's name, case-insensitive", required = false) String name,
            @P(value = "Exact skill name, e.g. Java", required = false) String skill,
            @P(value = "Exact company name", required = false) String company,
            @P(value = "Employment status: ACTIVE, ON_LEAVE or TERMINATED", required = false) String status,
            @P(value = "Department id (use findDepartments to get it)", required = false) String departmentId,
            @P(value = "Office id (use findOffices to get it)", required = false) String officeId,
            @P(value = "Only people allocated at most this percent on open projects, 0-100", required = false)
            Integer maxAllocationPercent) {

        EmployeeFilter filter = new EmployeeFilter(blankToNull(name), parseStatus(status), blankToNull(departmentId),
                blankToNull(skill), blankToNull(company), blankToNull(officeId), maxAllocationPercent);

        Page<EmployeeResponse> page = employees.search(filter, PageRequest.of(0, MAX_RESULTS));
        return listResult(page.getTotalElements(),
                page.getContent().stream().map(OrgTools::brief).toList());
    }

    @Tool("Get full details of one employee by id: job title, email, hire date, status, department, "
            + "manager, company, skills and number of direct reports.")
    public EmployeeResponse getEmployee(@P("Employee id") String employeeId) {
        return employees.findById(employeeId);
    }

    @Tool("Get the chain of managers above an employee, starting with their direct manager "
            + "and ending at the top of the organisation.")
    public Object getReportingChain(@P("Employee id") String employeeId) {
        return employees.reportingChain(employeeId);
    }

    @Tool("List the people who report directly to an employee.")
    public Map<String, Object> getDirectReports(@P("Manager's employee id") String employeeId) {
        var page = employees.directReports(employeeId, PageRequest.of(0, MAX_LIST_RESULTS));
        return listResult(page.getTotalElements(), page.getContent());
    }

    @Tool("List everyone below an employee in the org chart, at any depth (their whole team).")
    public Map<String, Object> getAllReports(@P("Manager's employee id") String employeeId) {
        var page = employees.allReports(employeeId, PageRequest.of(0, MAX_LIST_RESULTS));
        return listResult(page.getTotalElements(), page.getContent());
    }

    @Tool("Find departments by name. Returns id, code, parent department, head and member counts. "
            + "Leave the name empty to list departments.")
    public Map<String, Object> findDepartments(
            @P(value = "Part of the department name, case-insensitive", required = false) String name) {

        String needle = blankToNull(name) == null ? null : name.trim().toLowerCase(Locale.ROOT);

        List<DepartmentResponse> matches = departments
                .findAll(false, PageRequest.of(0, MAX_DEPARTMENTS_SCANNED)).getContent().stream()
                .filter(d -> needle == null || d.name().toLowerCase(Locale.ROOT).contains(needle)
                        || d.code().toLowerCase(Locale.ROOT).contains(needle))
                .limit(MAX_RESULTS)
                .toList();

        return listResult(matches.size(), matches);
    }

    @Tool("List the employees in a department, optionally including everyone in its sub-departments.")
    public Map<String, Object> getDepartmentMembers(
            @P("Department id") String departmentId,
            @P(value = "Also include members of all sub-departments", required = false)
            Boolean includeSubDepartments) {

        Page<EmployeeResponse> page = departments.members(departmentId,
                Boolean.TRUE.equals(includeSubDepartments), PageRequest.of(0, MAX_LIST_RESULTS));
        return listResult(page.getTotalElements(),
                page.getContent().stream().map(OrgTools::brief).toList());
    }

    @Tool("Find offices by name or city. Returns id, code, city, country and number of employees. "
            + "Leave empty to list offices.")
    public Map<String, Object> findOffices(
            @P(value = "Part of the office name or city, case-insensitive", required = false) String nameOrCity) {

        var page = offices.search(blankToNull(nameOrCity), PageRequest.of(0, MAX_RESULTS));
        return listResult(page.getTotalElements(), page.getContent());
    }

    @Tool("Find projects by name and/or status. Returns id, code, status, dates, owning department, "
            + "lead and member count.")
    public Map<String, Object> findProjects(
            @P(value = "Part of the project name, case-insensitive", required = false) String name,
            @P(value = "PLANNED, ACTIVE, ON_HOLD, COMPLETED or CANCELLED", required = false) String status) {

        var page = projects.search(blankToNull(name), parseProjectStatus(status), null,
                PageRequest.of(0, MAX_RESULTS));
        return listResult(page.getTotalElements(), page.getContent());
    }

    @Tool("List the people on a project with their role and allocation percent.")
    public Map<String, Object> getProjectMembers(@P("Project id") String projectId) {
        var page = projects.members(projectId, PageRequest.of(0, MAX_LIST_RESULTS));
        return listResult(page.getTotalElements(), page.getContent());
    }

    @Tool("List an employee's projects (open ones first) with role and allocation, plus their total "
            + "allocation on open projects.")
    public EmployeeProjectsResponse getEmployeeProjects(@P("Employee id") String employeeId) {
        return projects.projectsOf(employeeId);
    }

    // ---- Helpers ----------------------------------------------------------

    // Tells the model when a list was cut short, so it doesn't present a partial list as complete.
    private static Map<String, Object> listResult(long total, List<?> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("showing", items.size());
        result.put("results", items);
        return result;
    }

    private static Map<String, Object> brief(EmployeeResponse e) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("id", e.id());
        brief.put("name", e.name());
        brief.put("jobTitle", e.jobTitle());
        brief.put("status", e.status());
        brief.put("department", e.department() == null ? null : e.department().name());
        brief.put("office", e.office() == null ? null : e.office().name() + " (" + e.office().city() + ")");
        brief.put("manager", e.manager() == null ? null : e.manager().name());
        brief.put("allocationPercent", e.allocationPercent());
        return brief;
    }

    private static EmploymentStatus parseStatus(String status) {
        if (blankToNull(status) == null) {
            return null;
        }
        try {
            return EmploymentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status + "'. Use ACTIVE, ON_LEAVE or TERMINATED.");
        }
    }

    private static ProjectStatus parseProjectStatus(String status) {
        if (blankToNull(status) == null) {
            return null;
        }
        try {
            return ProjectStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown project status '" + status
                    + "'. Use PLANNED, ACTIVE, ON_HOLD, COMPLETED or CANCELLED.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
