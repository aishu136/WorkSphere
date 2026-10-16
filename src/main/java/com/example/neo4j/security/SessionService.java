package com.example.neo4j.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.TokenResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.RefreshToken;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.RefreshTokenRepository;

/*
 * Sessions: refresh tokens and revoking access.
 *
 * - Login gives a short-lived access token (a JWT) and a refresh token. The refresh token gets a new
 *   pair; each can be used once, so a stolen one stops working as soon as the owner refreshes.
 * - Using a refresh token that was already used means two parties have it. Every session of that
 *   login is then revoked, so the thief and the owner both have to log in again.
 * - Revoking sets AppUser.tokensValidAfter, which TokenValidity checks on every request: access
 *   tokens issued before it stop working at once, not when they expire.
 */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	/** A new refresh token, returned to the client once and never stored as is. */
	public record IssuedRefreshToken(String value, long expiresInSeconds) {
	}

	private final SecureRandom random = new SecureRandom();
	private final RefreshTokenRepository tokens;
	private final AppUserRepository users;
	private final UserDetailsService userDetails;
	private final TokenService tokenService;
	private final AuditLog auditLog;
	private final Duration refreshLifetime;

	public SessionService(RefreshTokenRepository tokens, AppUserRepository users, UserDetailsService userDetails,
			TokenService tokenService, AuditLog auditLog,
			@Value("${app.jwt.refresh-days:7}") long refreshDays) {
		this.tokens = tokens;
		this.users = users;
		this.userDetails = userDetails;
		this.tokenService = tokenService;
		this.auditLog = auditLog;
		this.refreshLifetime = Duration.ofDays(refreshDays);
	}

	@Transactional
	public IssuedRefreshToken create(String username) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		Instant now = Instant.now();
		RefreshToken token = new RefreshToken();
		token.setTokenHash(hash(value));
		token.setUsername(username);
		token.setCreatedAt(now);
		token.setExpiresAt(now.plus(refreshLifetime));
		tokens.save(token);

		return new IssuedRefreshToken(value, refreshLifetime.toSeconds());
	}

	/**
	 * A new access token and refresh token for a valid refresh token, which is used up.
	 * Reuse revokes everything; the revocation is kept even though the call fails.
	 */
	@Transactional(noRollbackFor = AuthenticationException.class)
	public TokenResponse refresh(String value) {

		RefreshToken token = tokens.findByTokenHash(hash(value))
				.orElseThrow(() -> new BadCredentialsException("Unknown refresh token"));
		Instant now = Instant.now();

		if (token.getRevokedAt() != null) {
			log.warn("Refresh token for {} was used again after it was revoked; revoking all sessions",
					token.getUsername());
			revokeEverything(token.getUsername(), now, AuditAction.REFRESH_TOKEN_REUSED);
			throw new BadCredentialsException("Refresh token already used");
		}
		if (!token.getExpiresAt().isAfter(now)) {
			throw new BadCredentialsException("Refresh token expired");
		}

		UserDetails user = userDetails.loadUserByUsername(token.getUsername());
		if (!user.isEnabled()) {
			throw new DisabledException("Login is disabled");
		}

		token.setRevokedAt(now);
		tokens.save(token);

		return tokenService.issue(
				UsernamePasswordAuthenticationToken.authenticated(user.getUsername(), null, user.getAuthorities()),
				create(user.getUsername()));
	}

	/** Logs this login out everywhere: every refresh token and access token stops working. */
	@Transactional
	public void logout(String username) {
		revokeEverything(username, Instant.now(), AuditAction.LOGOUT);
	}

	/** ADMIN: ends every session of a login, e.g. after a lost laptop. */
	@Transactional
	public void revokeSessions(String userId) {
		AppUser user = users.findById(userId)
				.orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
		revokeEverything(user.getUsername(), Instant.now(), AuditAction.SESSIONS_REVOKED);
	}

	// Expired tokens are useless; keep the collection small.
	@Scheduled(cron = "${app.jwt.cleanup-cron:0 30 3 * * *}")
	@Transactional
	public void deleteExpired() {
		long deleted = tokens.deleteExpired(Instant.now());
		if (deleted > 0) {
			log.info("Deleted {} expired refresh tokens", deleted);
		}
	}

	private void revokeEverything(String username, Instant now, AuditAction action) {
		long revoked = tokens.revokeAll(username, now);
		users.findByUsername(username).ifPresent(user -> {
			// JWT issue times are whole seconds, so round up: tokens from this second are refused too.
			user.setTokensValidAfter(now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
			users.save(user);
		});
		auditLog.record(action, AuditTargetType.USER, username, Map.of("refreshTokensRevoked", revoked));
	}

	static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
