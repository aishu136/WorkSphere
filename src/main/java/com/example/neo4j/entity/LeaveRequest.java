package com.example.neo4j.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Version;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.support.UUIDStringGenerator;

import lombok.Getter;
import lombok.Setter;

/**
 * An employee's request for time off, decided by a manager above them or by HR.
 *
 * The employee is referenced by id (not a relationship) so the request stays readable as
 * history whatever happens to them later, like StaffingRequest.
 */
@Node("LeaveRequest")
@Getter
@Setter
public class LeaveRequest {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Optimistic locking: if two people decide the same request at once, the second gets a conflict.
    @Version
    private Long version;

    private String employeeId;

    private LeaveType type;

    // Both inclusive.
    private LocalDate startDate;

    private LocalDate endDate;

    // Monday to Friday between the dates, counted when the request is made.
    private int workingDays;

    // The employee's note.
    private String note;

    private LeaveStatus status = LeaveStatus.PENDING;

    // Usernames.
    private String requestedBy;

    private Instant requestedAt;

    private String decidedBy;

    private Instant decidedAt;

    // Required when rejecting.
    private String reason;
}
