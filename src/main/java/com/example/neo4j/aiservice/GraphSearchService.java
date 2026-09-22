package com.example.neo4j.aiservice;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.neo4j.dto.AIRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeResponse;
import com.example.neo4j.dto.SkillResponse;
import com.example.neo4j.repository.EmployeeQueries;

// Builds the AI prompt context from the graph. Called by the Camel route in AIRoute.
@Service("graphSearchService")
public class GraphSearchService {

    // Caps how many matching employees go into one AI prompt (cost and context size).
    private static final int AI_CONTEXT_LIMIT = 20;

    private final EmployeeQueries employeeQueries;

    public GraphSearchService(EmployeeQueries employeeQueries) {
        this.employeeQueries = employeeQueries;
    }

    public String buildPrompt(AIRequest request) {

        List<EmployeeResponse> employees = employeeQueries.search(
                EmployeeFilter.byName(request.getEmployeeName()),
                PageRequest.of(0, AI_CONTEXT_LIMIT)).getContent();

        StringBuilder context = new StringBuilder();

        for (EmployeeResponse employee : employees) {

            context.append("Name : ").append(employee.name()).append("\n");
            context.append("Status : ").append(employee.status()).append("\n");

            if (employee.jobTitle() != null) {
                context.append("Job title : ").append(employee.jobTitle()).append("\n");
            }
            if (employee.department() != null) {
                context.append("Department : ").append(employee.department().name()).append("\n");
            }
            if (employee.manager() != null) {
                context.append("Manager : ").append(employee.manager().name()).append("\n");
            }
            if (employee.company() != null) {
                context.append("Company : ").append(employee.company().name()).append("\n");
            }
            if (!employee.skills().isEmpty()) {
                context.append("Skills : ")
                        .append(employee.skills().stream().map(SkillResponse::name).collect(Collectors.joining(", ")))
                        .append("\n");
            }
            context.append("Direct reports : ").append(employee.directReportCount()).append("\n\n");
        }

        return """
                Answer ONLY from this context.

                %s

                Question:
                %s
                """.formatted(context,
                request.getQuestion());

    }
}
