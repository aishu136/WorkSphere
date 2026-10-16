package com.example.neo4j.dto;

// expiresIn and refreshExpiresIn are in seconds. Send refreshToken to /auth/refresh for a new pair.
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                            long refreshExpiresIn) {
}
