package com.example.neo4j.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class OutboxRetryScheduleTests {

    @Test
    void retriesBackOffExponentiallyUpToAnHour() {
        assertThat(OutboxService.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OutboxService.retryDelay(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OutboxService.retryDelay(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(OutboxService.retryDelay(6)).isEqualTo(Duration.ofMinutes(32));
        assertThat(OutboxService.retryDelay(7)).isEqualTo(Duration.ofHours(1));
        assertThat(OutboxService.retryDelay(100)).isEqualTo(Duration.ofHours(1));
    }
}
