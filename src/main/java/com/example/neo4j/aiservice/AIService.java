package com.example.neo4j.aiservice;

import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Service;

import com.example.neo4j.dto.AIRequest;

@Service
public class AIService {

    private final ProducerTemplate producerTemplate;

    public AIService(ProducerTemplate producerTemplate) {
        this.producerTemplate = producerTemplate;
    }

    public String ask(AIRequest request) {

        return producerTemplate.requestBody(
                "direct:askAI",
                request,
                String.class);

    }

}
