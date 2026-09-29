package com.example.neo4j.dto;

/**
 * The assistant's answer.
 *
 * @param conversationId send it back with the next question to ask a follow-up. It changes when
 *                       the previous conversation had expired and a new one was started.
 * @param fallback       true when the assistant failed and the answer is a stand-in message;
 *                       the question was not answered and can be retried.
 */
public record AIResponse(String conversationId, String answer, boolean fallback) {
}
