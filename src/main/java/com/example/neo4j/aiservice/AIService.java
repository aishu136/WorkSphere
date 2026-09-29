package com.example.neo4j.aiservice;

import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Service;

import com.example.neo4j.aiservice.agent.Caller;
import com.example.neo4j.dto.AIRequest;
import com.example.neo4j.dto.AIResponse;
import com.example.neo4j.route.AIRoute;

@Service
public class AIService {

    private final ProducerTemplate producerTemplate;

    public AIService(ProducerTemplate producerTemplate) {
        this.producerTemplate = producerTemplate;
    }

    public AIResponse ask(AIRequest request, Caller caller) {

        return producerTemplate.requestBodyAndHeader(
                AIRoute.ENDPOINT,
                request,
                AIRoute.CALLER_HEADER,
                caller,
                AIResponse.class);

    }

}
