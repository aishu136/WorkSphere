package com.example.neo4j.security;

import java.time.Instant;
import java.util.Optional;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.neo4j.entity.AppUser;
import com.example.neo4j.repository.AppUserRepository;

/**
 * Checks every access token against its login, so access can be taken away before the token
 * expires: the login must still exist and be enabled (terminating an employee disables theirs),
 * and the token must be issued after the login's last logout or session revocation.
 *
 * This costs one lookup per request.
 */
@Component
public class TokenValidity implements OAuth2TokenValidator<Jwt> {

    private final AppUserRepository users;

    public TokenValidity(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Optional<AppUser> user = users.findByUsername(jwt.getSubject());
        if (user.isEmpty() || !user.get().isEnabled()) {
            return failure("The login no longer exists or is disabled");
        }
        Instant validAfter = user.get().getTokensValidAfter();
        Instant issuedAt = jwt.getIssuedAt();
        if (validAfter != null && (issuedAt == null || issuedAt.isBefore(validAfter))) {
            return failure("The token was revoked");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
