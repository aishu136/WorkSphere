package com.example.neo4j.aiservice.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

class ConversationStoreTests {

    /** A clock the test moves forward by hand. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-29T09:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();

    private static void turn(ConversationStore store, String user, String id, String text) {
        store.append(user, id, UserMessage.from("q " + text), AiMessage.from("a " + text));
    }

    @Test
    void keepsQuestionsAndAnswersInOrder() {
        ConversationStore store = new ConversationStore(Duration.ofMinutes(30), 100, clock);
        turn(store, "emma", "c1", "1");
        turn(store, "emma", "c1", "2");

        List<ChatMessage> history = store.history("emma", "c1").orElseThrow();
        assertThat(history).hasSize(4);
        assertThat(((UserMessage) history.get(0)).singleText()).isEqualTo("q 1");
        assertThat(((AiMessage) history.get(3)).text()).isEqualTo("a 2");
    }

    @Test
    void conversationsBelongToOneUser() {
        ConversationStore store = new ConversationStore(Duration.ofMinutes(30), 100, clock);
        turn(store, "emma", "c1", "1");

        assertThat(store.history("harriet", "c1")).isEmpty();
        assertThat(store.history("emma", null)).isEmpty();
    }

    @Test
    void keepsOnlyTheLastTurns() {
        ConversationStore store = new ConversationStore(Duration.ofMinutes(30), 100, clock);
        for (int i = 1; i <= ConversationStore.MAX_TURNS + 3; i++) {
            turn(store, "emma", "c1", String.valueOf(i));
        }

        List<ChatMessage> history = store.history("emma", "c1").orElseThrow();
        assertThat(history).hasSize(2 * ConversationStore.MAX_TURNS);
        assertThat(((UserMessage) history.get(0)).singleText()).isEqualTo("q 4");
    }

    @Test
    void conversationsExpireAfterInactivity() {
        ConversationStore store = new ConversationStore(Duration.ofMinutes(30), 100, clock);
        turn(store, "emma", "c1", "1");

        clock.now = clock.now.plus(Duration.ofMinutes(29));
        assertThat(store.history("emma", "c1")).isPresent();
        turn(store, "emma", "c1", "2"); // activity extends it

        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(store.history("emma", "c1")).isEmpty();

        // Continuing an expired id starts afresh.
        turn(store, "emma", "c1", "3");
        assertThat(store.history("emma", "c1").orElseThrow()).hasSize(2);
    }

    @Test
    void dropsTheLeastRecentlyUsedConversationsOverTheCap() {
        ConversationStore store = new ConversationStore(Duration.ofMinutes(30), 2, clock);
        turn(store, "emma", "old", "1");
        clock.now = clock.now.plusSeconds(1);
        turn(store, "emma", "middle", "1");
        clock.now = clock.now.plusSeconds(1);
        turn(store, "emma", "new", "1");

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.history("emma", "old")).isEmpty();
        assertThat(store.history("emma", "middle")).isPresent();
        assertThat(store.history("emma", "new")).isPresent();
    }
}
