package com.example.neo4j.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.LoginRequest;
import com.example.neo4j.dto.MeResponse;
import com.example.neo4j.dto.RefreshRequest;
import com.example.neo4j.dto.TokenResponse;
import com.example.neo4j.exception.TooManyLoginAttemptsException;
import com.example.neo4j.security.LoginAttempts;
import com.example.neo4j.security.SessionService;
import com.example.neo4j.security.TokenService;
import com.example.neo4j.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final SessionService sessions;
    private final LoginAttempts attempts;
    private final UserService userService;
    private final AuditLog auditLog;

    public AuthController(AuthenticationManager authenticationManager, TokenService tokenService,
                          SessionService sessions, LoginAttempts attempts, UserService userService,
                          AuditLog auditLog) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.sessions = sessions;
        this.attempts = attempts;
        this.userService = userService;
        this.auditLog = auditLog;
    }

    // Every attempt is audited. The response for a failure stays generic; the audit log
    // records the real reason for administrators. Too many failures lock the username or the
    // client address out for a while (see LoginAttempts).
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {

        String address = http.getRemoteAddr();
        Optional<Duration> locked = attempts.lockedFor(request.username(), address);
        if (locked.isPresent()) {
            auditLog.recordAs(request.username(), AuditAction.LOGIN_FAILED, AuditTargetType.USER,
                    request.username(), Map.of("reason", "LOCKED_OUT"));
            throw new TooManyLoginAttemptsException(locked.get());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            attempts.failed(request.username(), address);
            String reason = ex instanceof DisabledException ? "ACCOUNT_DISABLED" : "BAD_CREDENTIALS";
            auditLog.recordAs(request.username(), AuditAction.LOGIN_FAILED, AuditTargetType.USER,
                    request.username(), Map.of("reason", reason));
            throw ex;
        }

        attempts.succeeded(authentication.getName());
        auditLog.recordAs(authentication.getName(), AuditAction.LOGIN_SUCCEEDED, AuditTargetType.USER,
                authentication.getName(), Map.of());

        return ResponseEntity.ok(tokenService.issue(authentication, sessions.create(authentication.getName())));
    }

    // A new access token and refresh token. Each refresh token works once.
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody @Valid RefreshRequest request) {
        return ResponseEntity.ok(sessions.refresh(request.refreshToken()));
    }

    // Logs out everywhere: every token of this login stops working.
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        sessions.logout(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {

        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replaceFirst("^ROLE_", ""))
                .toList();

        return ResponseEntity.ok(new MeResponse(authentication.getName(), roles,
                userService.findEmployeeId(authentication.getName()).orElse(null)));
    }
}
