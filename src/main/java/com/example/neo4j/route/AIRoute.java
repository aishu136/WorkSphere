package com.example.neo4j.route;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.neo4j.aiservice.agent.OrgAssistantAgent;
import com.example.neo4j.dto.AIRequest;
import com.example.neo4j.dto.AIResponse;

/*
 * The AI assistant endpoint: body is the AIRequest, header CALLER_HEADER the Caller; replies with an AIResponse.
 *
 * Reliability:
 * - Each model call has a timeout and retries throttling/temporary errors (AIConfig), and a question
 *   has an overall time budget (OrgAssistantAgent). The whole question is not retried here: that
 *   would repeat every lookup and model call made so far.
 * - Any failure becomes a stand-in answer marked fallback=true instead of a 500, and is logged
 *   with how long the request ran.
 */
@Component
public class AIRoute extends RouteBuilder {

    public static final String ENDPOINT = "direct:askAI";
    public static final String CALLER_HEADER = "aiCaller";

    static final String FALLBACK_ANSWER = "Sorry, the assistant is unavailable right now. Please try again in a moment.";

    private static final Logger log = LoggerFactory.getLogger(AIRoute.class);

    private final OrgAssistantAgent agent;

    public AIRoute(OrgAssistantAgent agent) {
        this.agent = agent;
    }

    @Override
    public void configure() {

        onException(Exception.class)
                .handled(true)
                .process(this::fallback);

        // The agent looks up whatever it needs through its tools and returns the final answer.
        from(ENDPOINT)
                .routeId("askAI")
                .bean(agent, "ask(${body}, ${header." + CALLER_HEADER + "})")
                .process(exchange -> {
                    AIResponse response = exchange.getMessage().getBody(AIResponse.class);
                    log.info("AI answered in {} ms (conversation {})",
                            exchange.getClock().elapsed(), response.conversationId());
                    // Answers contain personal data, so they are only logged when debugging.
                    log.debug("AI Response : {}", response.answer());
                });
    }

    private void fallback(Exchange exchange) {

        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        log.error("AI request failed after {} ms", exchange.getClock().elapsed(), cause);

        // The bean failed, so the body is still the request. The question wasn't saved to the
        // conversation, so the caller can simply ask again.
        AIRequest request = exchange.getMessage().getBody(AIRequest.class);
        String conversationId = request == null ? null : request.getConversationId();
        exchange.getMessage().setBody(new AIResponse(conversationId, FALLBACK_ANSWER, true));
    }
}
