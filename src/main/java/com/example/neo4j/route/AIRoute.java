package com.example.neo4j.route;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.example.neo4j.aiservice.agent.OrgAssistantAgent;

@Component
public class AIRoute extends RouteBuilder {

    private final OrgAssistantAgent agent;

    public AIRoute(OrgAssistantAgent agent) {
        this.agent = agent;
    }

    @Override
    public void configure() {

        // The agent looks up whatever it needs through its tools and returns the final answer.
        from("direct:askAI")
                .bean(agent, "ask")
                .log("AI Response : ${body}");
    }
}
