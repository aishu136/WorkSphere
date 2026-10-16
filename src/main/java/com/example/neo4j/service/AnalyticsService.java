package com.example.neo4j.service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.dto.DepartmentSkillGapResponse;
import com.example.neo4j.dto.ManagerSpanResponse;
import com.example.neo4j.dto.OrgOverviewResponse;
import com.example.neo4j.dto.ProjectSkillRiskResponse;
import com.example.neo4j.repository.AnalyticsQueries;

/** Read-only figures about the organisation for HR. Nothing here changes data. */
@Service
public class AnalyticsService {

	private final AnalyticsQueries analyticsQueries;
	private final int maxSpan;

	public AnalyticsService(AnalyticsQueries analyticsQueries,
			@Value("${app.analytics.max-span:10}") int maxSpan) {
		this.analyticsQueries = analyticsQueries;
		this.maxSpan = maxSpan;
	}

	@Transactional(readOnly = true)
	public OrgOverviewResponse overview() {
		return analyticsQueries.overview();
	}

	/** Managers with the most direct reports first; WIDE above app.analytics.max-span, NARROW with one report. */
	@Transactional(readOnly = true)
	public Page<ManagerSpanResponse> managers(Pageable pageable) {
		return analyticsQueries.managers(maxSpan, pageable);
	}

	/** Open projects' skill coverage, most skills at risk first. With atRiskOnly, only projects with a risk. */
	@Transactional(readOnly = true)
	public List<ProjectSkillRiskResponse> projectSkillRisks(boolean atRiskOnly) {
		return analyticsQueries.projectSkillCoverage(LocalDate.now()).stream()
				.filter(project -> !atRiskOnly || project.skillsAtRisk() > 0)
				.sorted(Comparator.comparingInt(ProjectSkillRiskResponse::skillsAtRisk).reversed()
						.thenComparing(project -> project.project().name()))
				.toList();
	}

	/** Departments' skill supply against their open projects' needs, most shortages first. */
	@Transactional(readOnly = true)
	public List<DepartmentSkillGapResponse> skillGaps(String departmentId) {
		return analyticsQueries.departmentSkillSupply(departmentId).stream()
				.sorted(Comparator.comparingInt(DepartmentSkillGapResponse::skillsShort).reversed()
						.thenComparing(gap -> gap.department().name()))
				.toList();
	}
}
