package com.example.neo4j.entity;

import java.time.Instant;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

/**
 * A project lead's request to change a project's staffing, waiting for HR.
 *
 * Project and employee are referenced by id (not relationships) so the request stays
 * readable as history whatever happens to them later.
 */
@Node("StaffingRequest")
@Getter
@Setter
public class StaffingRequest {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Optimistic locking: if two HR users decide the same request at once, the second gets a conflict.
    @Version
    private Long version;

    private String projectId;

    private String employeeId;

    private StaffingAction action;

    // For ASSIGN only.
    private String role;

    private Integer allocationPercent;

    private StaffingRequestStatus status = StaffingRequestStatus.PENDING;

    // Usernames.
    private String requestedBy;

    private Instant requestedAt;

    private String decidedBy;

    private Instant decidedAt;

    // Required when rejecting.
    private String reason;
}
