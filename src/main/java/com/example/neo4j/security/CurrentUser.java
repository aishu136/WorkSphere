package com.example.neo4j.security;

import java.util.Set;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is performing the current action. */
public final class CurrentUser {

    /** Actor name used when nobody is logged in, e.g. the admin bootstrap at startup. */
    public static final String SYSTEM = "system";

    private static final Set<String> HR_OR_ADMIN = Set.of("ROLE_" + Role.HR.name(), "ROLE_" + Role.ADMIN.name());

    private CurrentUser() {
    }

    public static String username() {
        Authentication auth = authentication();
        return auth == null ? SYSTEM : auth.getName();
    }

    /** True for HR and ADMIN, false for everyone else (including when nobody is logged in). */
    public static boolean isHrOrAdmin() {
        Authentication auth = authentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(HR_OR_ADMIN::contains);
    }

    /** True when a logged-in user without HR/ADMIN rights made the change (i.e. manager self-service). */
    public static boolean isSelfService() {
        return authentication() != null && !isHrOrAdmin();
    }

    private static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth;
    }
}
