package com.example.neo4j.audit;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;

import jakarta.validation.Valid;

/*
 * Read-only access to the audit trail, newest first. There are deliberately no endpoints
 * to change or delete audit events.
 *
 * /audit (everything, including logins): ADMIN only.
 * /employees, /departments, /offices and /projects/{id}/history: HR or ADMIN.
 * Enforced in SecurityConfig.
 */
@RestController
public class AuditController {

    private final AuditLog auditLog;

    public AuditController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping("/audit")
    public ResponseEntity<PageResponse<AuditEventResponse>> search(
            @Valid @ParameterObject AuditFilter filter,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(auditLog.search(filter, paging.toPageable())));
    }

    @GetMapping("/employees/{id}/history")
    public ResponseEntity<PageResponse<AuditEventResponse>> employeeHistory(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(auditLog.search(
                AuditFilter.forTarget(AuditTargetType.EMPLOYEE, id), paging.toPageable())));
    }

    @GetMapping("/departments/{id}/history")
    public ResponseEntity<PageResponse<AuditEventResponse>> departmentHistory(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(auditLog.search(
                AuditFilter.forTarget(AuditTargetType.DEPARTMENT, id), paging.toPageable())));
    }

    @GetMapping("/offices/{id}/history")
    public ResponseEntity<PageResponse<AuditEventResponse>> officeHistory(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(auditLog.search(
                AuditFilter.forTarget(AuditTargetType.OFFICE, id), paging.toPageable())));
    }

    // Includes membership changes (who joined or left, and allocation changes).
    @GetMapping("/projects/{id}/history")
    public ResponseEntity<PageResponse<AuditEventResponse>> projectHistory(
            @PathVariable("id") String id,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(auditLog.search(
                AuditFilter.forTarget(AuditTargetType.PROJECT, id), paging.toPageable())));
    }
}
