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
import com.example.neo4j.entity.EmploymentStatus;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tools the AI assistant can call. They are deliberately read-only and limited to data every
 * logged-in user can already read through the API (directory, org chart, departments), so the
 * assistant can never change anything or reveal more than the caller could see anyway.
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

    public OrgTools(EmployeeService employees, DepartmentService departments) {
        this.employees = employees;
        this.departments = departments;
    }

    @Tool("Search the employee directory. All filters are optional and combine with AND. "
            + "Returns matching employees with their id, job title, department, manager and status.")
    public Map<String, Object> searchEmployees(
            @P(value = "Start of the employee's name, case-insensitive", required = false) String name,
            @P(value = "Exact skill name, e.g. Java", required = false) String skill,
            @P(value = "Exact company name", required = false) String company,
            @P(value = "Employment status: ACTIVE, ON_LEAVE or TERMINATED", required = false) String status) {

        EmployeeFilter filter = new EmployeeFilter(blankToNull(name), parseStatus(status), null,
                blankToNull(skill), blankToNull(company));

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
        brief.put("manager", e.manager() == null ? null : e.manager().name());
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
