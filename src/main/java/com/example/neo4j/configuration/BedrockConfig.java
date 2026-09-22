package com.example.neo4j.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
public class BedrockConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient() {

        return BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}