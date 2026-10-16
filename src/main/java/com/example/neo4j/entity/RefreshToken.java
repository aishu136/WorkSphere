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
 * A refresh token, which gets a new access token without logging in again. Only a SHA-256 hash of
 * the token is stored, so the database never holds a usable token. Each one can be used once:
 * using it revokes it and issues a new one.
 */
@Node("RefreshToken")
@Getter
@Setter
public class RefreshToken {

    @Id
    @GeneratedValue(UUIDStringGenerator.class)
    private String id;

    // Two refreshes with the same token at the same moment: only the first one succeeds.
    @Version
    private Long version;

    private String tokenHash;

    private String username;

    private Instant createdAt;

    private Instant expiresAt;

    // Set when it is used, at logout, or when its sessions are revoked.
    private Instant revokedAt;
}
