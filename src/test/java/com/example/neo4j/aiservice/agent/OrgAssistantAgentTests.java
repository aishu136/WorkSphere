package com.example.neo4j.aiservice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.example.neo4j.dto.AIRequest;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Tests the LangGraph4j agent loop with a scripted model (no Bedrock) and mocked services.
 */
class OrgAssistantAgentTests {

    /** Replays scripted replies and records every request the graph sends to the model. */
    static class ScriptedChatModel implements ChatModel {

        final List<ChatRequest> requests = new ArrayList<>();
        private final Deque<Function<ChatRequest, AiMessage>> script = new ArrayDeque<>();
        private Function<ChatRequest, AiMessage> fallback;

        ScriptedChatModel then(Function<ChatRequest, AiMessage> reply) {
            script.add(reply);
            return this;
        }

        ScriptedChatModel always(Function<ChatRequest, AiMessage> reply) {
            fallback = reply;
            return this;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            Function<ChatRequest, AiMessage> reply = script.isEmpty() ? fallback : script.poll();
            return ChatResponse.builder().aiMessage(reply.apply(request)).build();
        }
    }

    private static AiMessage callTool(String id, String name, String argumentsJson) {
        return AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(argumentsJson).build());
    }

    private static List<ToolExecutionResultMessage> toolResults(ChatRequest request) {
        return request.messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .toList();
    }

    private static AIRequest question(String employeeName, String text) {
        AIRequest request = new AIRequest();
        request.setEmployeeName(employeeName);
        request.setQuestion(text);
        return request;
    }

    private EmployeeService employees;
    private DepartmentService departments;
    private ScriptedChatModel model;

    @BeforeEach
    void setUp() {
        employees = mock(EmployeeService.class);
        departments = mock(DepartmentService.class);
        model = new ScriptedChatModel();
    }

    private OrgAssistantAgent agent() throws Exception {
        return new OrgAssistantAgent(model, new OrgTools(employees, departments));
    }

    @Test
    void answersDirectlyWhenNoLookupIsNeeded() throws Exception {
        model.then(request -> AiMessage.from("Hello! Ask me about the organisation."));

        assertThat(agent().ask(question(null, "Hi"))).isEqualTo("Hello! Ask me about the organisation.");

        ChatRequest first = model.requests.get(0);
        assertThat(first.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) first.messages().get(1)).singleText()).isEqualTo("Hi");
        assertThat(first.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("searchEmployees", "getEmployee", "getReportingChain",
                        "getDirectReports", "getAllReports", "findDepartments", "getDepartmentMembers");
    }

    @Test
    void runsRequestedToolsAndFeedsResultsBackToTheModel() throws Exception {
        when(employees.reportingChain("dev-1")).thenReturn(List.of(
                new EmployeeSummary("m-1", "Lena Lead", "Team Lead"),
                new EmployeeSummary("c-1", "Chitra CTO", "CTO")));

        model.then(request -> callTool("call-1", "getReportingChain", "{\"employeeId\":\"dev-1\"}"))
                .then(request -> AiMessage.from("Dev reports to Lena Lead, who reports to Chitra CTO."));

        String answer = agent().ask(question("Dev", "Who is Dev's manager chain?"));

        assertThat(answer).isEqualTo("Dev reports to Lena Lead, who reports to Chitra CTO.");
        assertThat(model.requests).hasSize(2);

        // The employee name hint is part of the question.
        assertThat(((UserMessage) model.requests.get(0).messages().get(1)).singleText())
                .contains("\"Dev\"").contains("Who is Dev's manager chain?");

        // Second model call sees the tool result with real data.
        List<ToolExecutionResultMessage> results = toolResults(model.requests.get(1));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("call-1");
        assertThat(results.get(0).text()).contains("Lena Lead").contains("Chitra CTO");
    }

    @Test
    void runsSeveralToolCallsFromOneTurn() throws Exception {
        when(employees.search(eq(EmployeeFilter.byName("pri")), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(employees.directReports(eq("m-1"), any()))
                .thenReturn(new PageImpl<>(List.of(new EmployeeSummary("d-1", "Dev", "Engineer")),
                        PageRequest.of(0, 50), 1));

        model.then(request -> AiMessage.from(
                        ToolExecutionRequest.builder().id("a").name("searchEmployees")
                                .arguments("{\"name\":\"pri\"}").build(),
                        ToolExecutionRequest.builder().id("b").name("getDirectReports")
                                .arguments("{\"employeeId\":\"m-1\"}").build()))
                .then(request -> AiMessage.from("done"));

        assertThat(agent().ask(question(null, "q"))).isEqualTo("done");

        List<ToolExecutionResultMessage> results = toolResults(model.requests.get(1));
        assertThat(results).extracting(ToolExecutionResultMessage::id).containsExactly("a", "b");
        assertThat(results.get(0).text()).contains("\"total\":0");
        assertThat(results.get(1).text()).contains("\"total\":1").contains("Dev");
    }

    @Test
    void toolErrorsAreReturnedToTheModelInsteadOfFailingTheRequest() throws Exception {
        when(employees.findById("nope")).thenThrow(new ResourceNotFoundException("Employee not found with id: nope"));

        model.then(request -> callTool("1", "getEmployee", "{\"employeeId\":\"nope\"}"))
                .then(request -> callTool("2", "noSuchTool", "{}"))
                .then(request -> callTool("3", "searchEmployees", "{\"status\":\"RETIRED\"}"))
                .then(request -> AiMessage.from("I couldn't find that employee."));

        assertThat(agent().ask(question(null, "Who is nope?"))).isEqualTo("I couldn't find that employee.");

        List<ToolExecutionResultMessage> results = toolResults(model.requests.get(3));
        assertThat(results.get(0).text()).startsWith("Error:").contains("Employee not found with id: nope");
        assertThat(results.get(1).text()).isEqualTo("Error: there is no tool named noSuchTool");
        assertThat(results.get(2).text()).startsWith("Error:").contains("Unknown status");
    }

    @Test
    void repeatedIdenticalMessagesAreKept() throws Exception {
        when(employees.reportingChain(any())).thenReturn(List.of());

        // Same tool call twice (same id and arguments), then an answer.
        model.then(request -> callTool("same", "getReportingChain", "{\"employeeId\":\"e\"}"))
                .then(request -> callTool("same", "getReportingChain", "{\"employeeId\":\"e\"}"))
                .then(request -> AiMessage.from("answer"));

        assertThat(agent().ask(question(null, "q"))).isEqualTo("answer");
        assertThat(model.requests).hasSize(3);
        assertThat(toolResults(model.requests.get(2))).hasSize(2);
    }

    @Test
    void stopsAfterTheToolRoundLimit() throws Exception {
        when(employees.reportingChain(any())).thenReturn(List.of());
        model.always(request -> callTool("x", "getReportingChain", "{\"employeeId\":\"loop\"}"));

        assertThat(agent().ask(question(null, "loop forever"))).isEqualTo(OrgAssistantAgent.STEP_LIMIT_ANSWER);

        // MAX_TOOL_ROUNDS rounds of tools, plus the final model call that was stopped.
        assertThat(model.requests).hasSize(OrgAssistantAgent.MAX_TOOL_ROUNDS + 1);
    }

    @Test
    void eachQuestionStartsWithAFreshConversation() throws Exception {
        model.always(request -> AiMessage.from("ok"));
        OrgAssistantAgent agent = agent();

        agent.ask(question(null, "first"));
        agent.ask(question(null, "second"));

        List<ChatMessage> secondConversation = model.requests.get(1).messages();
        assertThat(secondConversation).hasSize(2);
        assertThat(((UserMessage) secondConversation.get(1)).singleText()).isEqualTo("second");
    }
}
