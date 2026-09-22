package com.example.neo4j.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
