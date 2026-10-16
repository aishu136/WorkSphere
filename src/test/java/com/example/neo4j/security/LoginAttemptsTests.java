package com.example.neo4j.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptsTests {

    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2030-03-04T09:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    // 3 failures per username, 5 per address, counted over 10 minutes, locked out for 15.
    private final LoginAttempts attempts = new LoginAttempts(3, 5, Duration.ofMinutes(10), Duration.ofMinutes(15), clock);

    @Test
    void aUsernameIsLockedOutAfterTooManyFailures() {
        attempts.failed("Alice", "10.0.0.1");
        attempts.failed("alice", "10.0.0.2");
        assertThat(attempts.lockedFor("alice", "10.0.0.3")).isEmpty();

        attempts.failed("alice", "10.0.0.3");
        assertThat(attempts.lockedFor("ALICE", "10.0.0.9")).contains(Duration.ofMinutes(15));

        clock.advance(Duration.ofMinutes(10));
        assertThat(attempts.lockedFor("alice", "10.0.0.9")).contains(Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        assertThat(attempts.lockedFor("alice", "10.0.0.9")).isEmpty();

        // The lockout is over and so is the window: counting starts again.
        attempts.failed("alice", "10.0.0.9");
        assertThat(attempts.lockedFor("alice", "10.0.0.9")).isEmpty();
    }

    @Test
    void failuresSpreadOutPastTheWindowDontAddUp() {
        attempts.failed("bob", "10.0.0.1");
        attempts.failed("bob", "10.0.0.1");
        clock.advance(Duration.ofMinutes(11));
        attempts.failed("bob", "10.0.0.1");

        assertThat(attempts.lockedFor("bob", "10.0.0.1")).isEmpty();
    }

    @Test
    void anAddressGuessingManyUsernamesIsLockedOut() {
        for (String user : new String[] {"a", "b", "c", "d", "e"}) {
            attempts.failed(user, "10.6.6.6");
        }
        assertThat(attempts.lockedFor("someone-new", "10.6.6.6")).isPresent();
        assertThat(attempts.lockedFor("someone-new", "10.0.0.1")).isEmpty();
    }

    @Test
    void successClearsTheUsernamesCount() {
        attempts.failed("carol", "10.0.0.1");
        attempts.failed("carol", "10.0.0.1");
        attempts.succeeded("carol");
        attempts.failed("carol", "10.0.0.1");

        assertThat(attempts.lockedFor("carol", "10.0.0.2")).isEmpty();
    }
}
