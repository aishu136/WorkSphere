package com.example.neo4j.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import com.example.neo4j.audit.AuditAction;
import com.example.neo4j.audit.AuditEventResponse;
import com.example.neo4j.audit.AuditFilter;
import com.example.neo4j.audit.AuditLog;
import com.example.neo4j.audit.AuditTargetType;
import com.example.neo4j.dto.TokenResponse;
import com.example.neo4j.entity.AppUser;
import com.example.neo4j.entity.RefreshToken;
import com.example.neo4j.repository.AppUserRepository;
import com.example.neo4j.repository.RefreshTokenRepository;
import com.example.neo4j.security.SessionService.IssuedRefreshToken;
import com.example.neo4j.support.ServiceTest;
import com.example.neo4j.support.SharedNeo4jTest;

@ServiceTest
class SessionServiceTests extends SharedNeo4jTest {

    @Autowired
    SessionService sessions;

    @Autowired
    AppUserRepository users;

    @Autowired
    RefreshTokenRepository tokens;

    @Autowired
    AuditLog auditLog;

    private AppUser login(String username, boolean enabled) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash("{noop}unused");
        user.setRoles(List.of("EMPLOYEE"));
        user.setEnabled(enabled);
        return users.save(user);
    }

    private List<AuditAction> history(String username) {
        return auditLog.search(AuditFilter.forTarget(AuditTargetType.USER, username), PageRequest.of(0, 20))
                .getContent().stream().map(AuditEventResponse::action).toList();
    }

    @Test
    void aRefreshTokenWorksOnceAndIsReplaced() {
        login("dev", true);
        IssuedRefreshToken first = sessions.create("dev");

        TokenResponse next = sessions.refresh(first.value());
        assertThat(next.accessToken()).isNotBlank();
        assertThat(next.refreshToken()).isNotBlank().isNotEqualTo(first.value());
        assertThat(next.refreshExpiresIn()).isEqualTo(7 * 24 * 3600);

        // Only hashes are stored.
        assertThat(tokens.findAll()).extracting(RefreshToken::getTokenHash)
                .doesNotContain(first.value(), next.refreshToken())
                .contains(SessionService.hash(first.value()));

        // The new one works in turn.
        assertThat(sessions.refresh(next.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void usingARefreshTokenTwiceRevokesEverySession() {
        login("dev", true);
        IssuedRefreshToken stolen = sessions.create("dev");
        TokenResponse owner = sessions.refresh(stolen.value());
        Instant before = Instant.now();

        assertThatThrownBy(() -> sessions.refresh(stolen.value())).isInstanceOf(BadCredentialsException.class);

        // The owner's newer session is gone too, and their access tokens are refused from now on.
        assertThatThrownBy(() -> sessions.refresh(owner.refreshToken())).isInstanceOf(BadCredentialsException.class);
        assertThat(users.findByUsername("dev").orElseThrow().getTokensValidAfter()).isAfter(before);
        assertThat(history("dev")).contains(AuditAction.REFRESH_TOKEN_REUSED);
    }

    @Test
    void unknownExpiredAndDisabledAreRefused() {
        assertThatThrownBy(() -> sessions.refresh("made-up")).isInstanceOf(BadCredentialsException.class);

        login("dev", true);
        IssuedRefreshToken old = sessions.create("dev");
        RefreshToken stored = tokens.findByTokenHash(SessionService.hash(old.value())).orElseThrow();
        stored.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(stored);
        assertThatThrownBy(() -> sessions.refresh(old.value())).isInstanceOf(BadCredentialsException.class);

        login("gone", false);
        IssuedRefreshToken disabled = sessions.create("gone");
        assertThatThrownBy(() -> sessions.refresh(disabled.value())).isInstanceOf(DisabledException.class);
    }

    @Test
    void logoutAndAdminRevocationEndEverySession() {
        login("dev", true);
        IssuedRefreshToken laptop = sessions.create("dev");
        IssuedRefreshToken phone = sessions.create("dev");

        sessions.logout("dev");
        assertThatThrownBy(() -> sessions.refresh(laptop.value())).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.refresh(phone.value())).isInstanceOf(BadCredentialsException.class);
        assertThat(history("dev")).contains(AuditAction.LOGOUT);

        AppUser hr = login("hr", true);
        IssuedRefreshToken hrToken = sessions.create("hr");
        sessions.revokeSessions(hr.getId());
        assertThatThrownBy(() -> sessions.refresh(hrToken.value())).isInstanceOf(BadCredentialsException.class);
        assertThat(users.findByUsername("hr").orElseThrow().getTokensValidAfter()).isNotNull();
    }

    @Test
    void expiredTokensAreCleanedUp() {
        login("dev", true);
        IssuedRefreshToken live = sessions.create("dev");
        RefreshToken old = tokens.findByTokenHash(SessionService.hash(sessions.create("dev").value())).orElseThrow();
        old.setExpiresAt(Instant.now().minusSeconds(60));
        tokens.save(old);

        sessions.deleteExpired();

        assertThat(tokens.findAll()).extracting(RefreshToken::getTokenHash)
                .containsExactly(SessionService.hash(live.value()));
    }
}
