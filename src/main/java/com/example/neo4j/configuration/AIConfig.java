package com.example.neo4j.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import software.amazon.awssdk.regions.Region;

// The chat model used by the LangGraph4j assistant (aiservice.agent.OrgAssistantAgent).
@Configuration
public class AIConfig {

    @Bean
    public ChatModel chatModel() {
        return BedrockChatModel.builder()
                .region(Region.US_EAST_1)
                .modelId("amazon.nova-pro-v1:0")
                .build();
    }
}
