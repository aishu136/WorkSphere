package com.example.neo4j.route;

//
//
//import org.apache.camel.builder.RouteBuilder;
//import org.springframework.stereotype.Component;
//
//@Component
//public class AIRoute extends RouteBuilder {
//
//    @Override
//    public void configure() {
//
//        from("direct:askAI")
//                .bean("graphSearchService", "buildPrompt")
//                .bean("assistantBean", "chat")
//                .log("AI Response : ${body}");
//
//    }
//}


import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.example.neo4j.aiservice.Assistant;
import com.example.neo4j.aiservice.GraphSearchService;

@Component
public class AIRoute extends RouteBuilder {

    private final GraphSearchService graphSearchService;
    private final Assistant assistant;

    public AIRoute(GraphSearchService graphSearchService,
                   Assistant assistant) {
        this.graphSearchService = graphSearchService;
        this.assistant = assistant;
    }

    @Override
    public void configure() {

        from("direct:askAI")
                .bean(graphSearchService, "buildPrompt")
                .bean(assistant, "chat")
                .log("AI Response : ${body}");
    }
}