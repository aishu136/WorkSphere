package com.example.neo4j.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.neo4j.dto.TokenResponse;

@Service
public class TokenService {

    static final String ROLES_CLAIM = "roles";
    private static final String ISSUER = "employee-management";

    private final JwtEncoder encoder;
    private final Duration expiry;

    public TokenService(JwtEncoder encoder,
                        @Value("${app.jwt.expiry-minutes:60}") long expiryMinutes) {
        this.encoder = encoder;
        this.expiry = Duration.ofMinutes(expiryMinutes);
    }

    public TokenResponse issue(Authentication authentication) {

        Instant now = Instant.now();

        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replaceFirst("^ROLE_", ""))
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .subject(authentication.getName())
                .claim(ROLES_CLAIM, roles)
                .build();

        // The algorithm must be set explicitly; the encoder defaults to RS256.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new TokenResponse(token, "Bearer", expiry.toSeconds());
    }
}
