package com.example.neo4j.controller;

import java.util.List;
import java.util.Map;

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
import com.example.neo4j.dto.TokenResponse;
import com.example.neo4j.security.TokenService;
import com.example.neo4j.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final UserService userService;
    private final AuditLog auditLog;

    public AuthController(AuthenticationManager authenticationManager, TokenService tokenService,
                          UserService userService, AuditLog auditLog) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.userService = userService;
        this.auditLog = auditLog;
    }

    // Every attempt is audited. The response for a failure stays generic; the audit log
    // records the real reason for administrators.
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Valid LoginRequest request) {

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException ex) {
            String reason = ex instanceof DisabledException ? "ACCOUNT_DISABLED" : "BAD_CREDENTIALS";
            auditLog.recordAs(request.username(), AuditAction.LOGIN_FAILED, AuditTargetType.USER,
                    request.username(), Map.of("reason", reason));
            throw ex;
        }

        auditLog.recordAs(authentication.getName(), AuditAction.LOGIN_SUCCEEDED, AuditTargetType.USER,
                authentication.getName(), Map.of());

        return ResponseEntity.ok(tokenService.issue(authentication));
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
