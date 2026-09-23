package com.example.neo4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectStaffingRequest(

        // Shown to the requester and kept in the audit trail.
        @NotBlank
        @Size(max = 500)
        String reason) {
}
