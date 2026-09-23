package com.example.neo4j.dto;

import com.example.neo4j.entity.StaffingAction;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStaffingRequest(

        @NotBlank
        String employeeId,

        @NotNull
        StaffingAction action,

        // ASSIGN only.
        @Size(max = 100)
        String role,

        // Required for ASSIGN.
        @Min(1)
        @Max(100)
        Integer allocationPercent) {
}
