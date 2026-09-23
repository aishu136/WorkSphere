package com.example.neo4j.outbox;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.neo4j.dto.PageParams;
import com.example.neo4j.dto.PageResponse;

import jakarta.validation.Valid;

// ADMIN only (SecurityConfig): see stuck emails and put failed ones back in the queue.
@RestController
public class OutboxController {

    private final OutboxService outbox;

    public OutboxController(OutboxService outbox) {
        this.outbox = outbox;
    }

    // e.g. /outbox?status=FAILED, newest first.
    @GetMapping("/outbox")
    public ResponseEntity<PageResponse<OutboxEmailResponse>> search(
            @RequestParam(name = "status", required = false) OutboxStatus status,
            @Valid @ParameterObject PageParams paging) {

        return ResponseEntity.ok(PageResponse.of(outbox.search(status, paging.toPageable())));
    }

    @PostMapping("/outbox/{id}/retry")
    public ResponseEntity<OutboxEmailResponse> retry(@PathVariable("id") String id) {
        return ResponseEntity.ok(outbox.retry(id));
    }
}
