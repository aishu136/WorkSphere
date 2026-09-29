package com.example.neo4j.configuration;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import software.amazon.awssdk.regions.Region;

// The chat model used by the LangGraph4j assistant (aiservice.agent.OrgAssistantAgent).
@Configuration
public class AIConfig {

    // A timeout per model call, and retries for throttling and temporary Bedrock errors.
    @Bean
    public ChatModel chatModel(@Value("${app.ai.model-timeout:PT30S}") Duration timeout,
                               @Value("${app.ai.model-max-retries:2}") int maxRetries) {
        return BedrockChatModel.builder()
                .region(Region.US_EAST_1)
                .modelId("amazon.nova-pro-v1:0")
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }
}
