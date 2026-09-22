package com.example.neo4j.configuration;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import com.example.neo4j.aiservice.Assistant;
//
//import dev.langchain4j.model.bedrock.BedrockChatModel;
//import dev.langchain4j.model.chat.ChatModel;
//import dev.langchain4j.service.AiServices;
//import software.amazon.awssdk.regions.Region;
//
//@Configuration
//public class AIConfig {
//
//    @Bean
//    Assistant assistant(ChatModel chatModel) {
//        return AiServices.create(Assistant.class, chatModel);
//    }
//    
//    @Bean
//    ChatModel chatModel() {
//        return BedrockChatModel.builder()
//                .region(Region.US_EAST_1) // check Nova availability in your region
//                .modelId("amazon.nova-pro-v1:0") // or amazon.nova-lite-v1:0 / amazon.nova-micro-v1:0
//                .build();
//    }
//
//    
//    
//
//}


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.neo4j.aiservice.Assistant;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import software.amazon.awssdk.regions.Region;

@Configuration
public class AIConfig {

    @Bean
    public ChatModel chatModel() {
        return BedrockChatModel.builder()
                .region(Region.US_EAST_1)
                .modelId("amazon.nova-pro-v1:0")
                .build();
    }

    @Bean
    public Assistant assistant(ChatModel chatModel) {
        return AiServices.create(Assistant.class, chatModel);
    }
}