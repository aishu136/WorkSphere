package com.example.neo4j.aiservice.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * Recent assistant conversations, so a user can ask follow-up questions ("and who is their manager?").
 *
 * - Conversations belong to the user who started them: another user can't continue or read one,
 *   even with its id.
 * - Only each turn's question and final answer are kept, not the tool lookups: that keeps the
 *   context small, and the assistant looks data up again instead of trusting old results.
 * - Only the last {@link #MAX_TURNS} turns are kept, conversations expire after a period of
 *   inactivity, and the number of conversations is capped, so memory use stays bounded.
 *
 * Held in memory: conversations are lost on restart and not shared between instances.
 */
@Component
public class ConversationStore {

    static final int MAX_TURNS = 10;

    private record Key(String username, String conversationId) {
    }

    private static final class Conversation {
        final Deque<List<ChatMessage>> turns = new ArrayDeque<>();
        volatile Instant lastUsed;

        Conversation(Instant now) {
            lastUsed = now;
        }
    }

    private final Map<Key, Conversation> conversations = new ConcurrentHashMap<>();
    private final Duration timeToLive;
    private final int maxConversations;
    private final Clock clock;

    @Autowired
    public ConversationStore(@Value("${app.ai.conversation-ttl:PT30M}") Duration timeToLive,
                             @Value("${app.ai.max-conversations:10000}") int maxConversations) {
        this(timeToLive, maxConversations, Clock.systemUTC());
    }

    ConversationStore(Duration timeToLive, int maxConversations, Clock clock) {
        this.timeToLive = timeToLive;
        this.maxConversations = maxConversations;
        this.clock = clock;
    }

    /** The earlier turns of the user's conversation, oldest first; empty if unknown or expired. */
    public Optional<List<ChatMessage>> history(String username, String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        Conversation conversation = conversations.get(new Key(username, conversationId));
        if (conversation == null) {
            return Optional.empty();
        }
        synchronized (conversation) {
            if (isExpired(conversation)) {
                return Optional.empty();
            }
            List<ChatMessage> messages = new ArrayList<>();
            conversation.turns.forEach(messages::addAll);
            return Optional.of(messages);
        }
    }

    /** Adds one question and its answer, starting the conversation if needed. */
    public void append(String username, String conversationId, UserMessage question, AiMessage answer) {

        Conversation conversation = conversations.compute(new Key(username, conversationId),
                (key, existing) -> existing == null || isExpired(existing)
                        ? new Conversation(clock.instant()) : existing);

        synchronized (conversation) {
            conversation.turns.addLast(List.of(question, answer));
            while (conversation.turns.size() > MAX_TURNS) {
                conversation.turns.removeFirst();
            }
            conversation.lastUsed = clock.instant();
        }

        evict();
    }

    int size() {
        return conversations.size();
    }

    private boolean isExpired(Conversation conversation) {
        return conversation.lastUsed.plus(timeToLive).isBefore(clock.instant());
    }

    // Drops expired conversations, then the least recently used ones while over the cap.
    private void evict() {
        conversations.values().removeIf(this::isExpired);

        int excess = conversations.size() - maxConversations;
        if (excess > 0) {
            // Snapshot the times first: they can change while sorting.
            conversations.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().lastUsed))
                    .sorted(Map.Entry.comparingByValue())
                    .limit(excess)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(conversations::remove);
        }
    }
}
