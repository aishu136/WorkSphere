package com.example.neo4j.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing. Failed logins are counted per username and per client address over
 * a sliding window; past the limit, that username or address is locked out for a while, even with
 * the right password.
 *
 * Counts live in memory, so each application instance counts on its own and a restart clears them.
 * Behind a proxy, set server.forward-headers-strategy so the client's address is seen, not the
 * proxy's.
 */
@Component
public class LoginAttempts {

    // Beyond this many tracked keys, finished windows are dropped so memory stays bounded.
    private static final int MAX_TRACKED = 100_000;

    private record Window(int failures, Instant start, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerUser;
    private final int maxPerAddress;
    private final Duration window;
    private final Duration lockout;
    private final Clock clock;

    @Autowired
    public LoginAttempts(@Value("${app.auth.max-failures-per-user:5}") int maxPerUser,
                         @Value("${app.auth.max-failures-per-address:20}") int maxPerAddress,
                         @Value("${app.auth.failure-window:PT15M}") Duration window,
                         @Value("${app.auth.lockout:PT15M}") Duration lockout) {
        this(maxPerUser, maxPerAddress, window, lockout, Clock.systemUTC());
    }

    LoginAttempts(int maxPerUser, int maxPerAddress, Duration window, Duration lockout, Clock clock) {
        this.maxPerUser = maxPerUser;
        this.maxPerAddress = maxPerAddress;
        this.window = window;
        this.lockout = lockout;
        this.clock = clock;
    }

    /** How long until this username and address may try again, if either is locked out. */
    public Optional<Duration> lockedFor(String username, String address) {
        Instant now = clock.instant();
        return Stream.of(userKey(username), addressKey(address))
                .map(windows::get)
                .filter(w -> w != null && w.lockedUntil() != null && w.lockedUntil().isAfter(now))
                .map(w -> Duration.between(now, w.lockedUntil()))
                .max(Duration::compareTo);
    }

    public void failed(String username, String address) {
        Instant now = clock.instant();
        count(userKey(username), maxPerUser, now);
        count(addressKey(address), maxPerAddress, now);
        if (windows.size() > MAX_TRACKED) {
            windows.entrySet().removeIf(e -> finished(e.getValue(), now));
        }
    }

    /** A successful login clears the username's count; the address keeps its count. */
    public void succeeded(String username) {
        windows.remove(userKey(username));
    }

    /** Forgets all counts. */
    public void clear() {
        windows.clear();
    }

    private void count(String key, int max, Instant now) {
        windows.compute(key, (k, w) -> {
            Window current = w == null || finished(w, now) ? new Window(0, now, null) : w;
            int failures = current.failures() + 1;
            return new Window(failures, current.start(), failures >= max ? now.plus(lockout) : current.lockedUntil());
        });
    }

    private boolean finished(Window w, Instant now) {
        boolean locked = w.lockedUntil() != null && w.lockedUntil().isAfter(now);
        return !locked && !w.start().plus(window).isAfter(now);
    }

    private static String userKey(String username) {
        return "user:" + (username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }
}
