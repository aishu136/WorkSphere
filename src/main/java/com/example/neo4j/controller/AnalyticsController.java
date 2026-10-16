package com.example.neo4j.controller;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.dto.DepartmentSkillGapResponse;
import com.example.neo4j.dto.ManagerSpanResponse;
import com.example.neo4j.dto.OrgOverviewResponse;
import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse;
import com.example.neo4j.service.AnalyticsService;

import jakarta.validation.Valid;

// HR or ADMIN only (SecurityConfig).
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ResponseEntity<OrgOverviewResponse> overview() {
        return ResponseEntity.ok(service.overview());
    }

    @GetMapping("/managers")
    public ResponseEntity<PageResponse<ManagerSpanResponse>> managers(@Valid @ParameterObject PageParams paging) {
        return ResponseEntity.ok(PageResponse.of(service.managers(paging.toPageable())));
    }

    // The bus factor: required skills that one person, or nobody, on the project covers.
    @GetMapping("/project-skill-risks")
    public ResponseEntity<List<ProjectSkillRiskResponse>> projectSkillRisks(
            @RequestParam(name = "atRiskOnly", defaultValue = "true") boolean atRiskOnly) {

        return ResponseEntity.ok(service.projectSkillRisks(atRiskOnly));
    }

    @GetMapping("/skill-gaps")
    public ResponseEntity<List<DepartmentSkillGapResponse>> skillGaps(
            @RequestParam(name = "departmentId", required = false) String departmentId) {

        return ResponseEntity.ok(service.skillGaps(departmentId));
    }
}
