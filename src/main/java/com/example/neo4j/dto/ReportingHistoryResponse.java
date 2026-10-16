package com.example.neo4j.dto;

import java.util.List;

/** Who someone reported to, and which department they were in, over time. Oldest first. */
public record ReportingHistoryResponse(
        String employeeId,
        List<HistoryPeriod> managers,
        List<HistoryPeriod> departments) {
}
