package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectLeaveRequest(

        // Shown to the employee and kept in the audit trail.
        @NotBlank
        @Size(max = 500)
        String reason) {
}
