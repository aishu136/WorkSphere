package com.example.neo4j.aiservice.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import com.example.neo4j.dto.AIResponse;
import com.example.neo4j.dto.EmployeeFilter;
import com.example.neo4j.dto.EmployeeSummary;
import com.example.neo4j.dto.ProjectSummary;
import com.example.neo4j.dto.SkillCount;
import com.example.neo4j.dto.StaffingRequestResponse;
import com.example.neo4j.entity.ProjectStatus;
import com.example.neo4j.entity.StaffingAction;
import com.example.neo4j.entity.StaffingRequestStatus;
import com.example.neo4j.exception.ResourceNotFoundException;
import com.example.neo4j.service.DepartmentService;
import com.example.neo4j.service.EmployeeService;
import com.example.neo4j.service.OfficeService;
import com.example.neo4j.service.ProjectService;
import com.example.neo4j.service.StaffingService;

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

    private static final Caller EMPLOYEE = new Caller("emma", false);
    private static final Caller HR = new Caller("harriet", true);

    private static AIRequest question(String employeeName, String text) {
        AIRequest request = new AIRequest();
        request.setEmployeeName(employeeName);
        request.setQuestion(text);
        return request;
    }

    private EmployeeService employees;
    private DepartmentService departments;
    private OfficeService offices;
    private ProjectService projects;
    private StaffingService staffing;
    private ConversationStore conversations;
    private ScriptedChatModel model;

    @BeforeEach
    void setUp() {
        employees = mock(EmployeeService.class);
        departments = mock(DepartmentService.class);
        offices = mock(OfficeService.class);
        projects = mock(ProjectService.class);
        staffing = mock(StaffingService.class);
        conversations = new ConversationStore(Duration.ofMinutes(30), 100);
        model = new ScriptedChatModel();
    }

    private OrgAssistantAgent agent() throws Exception {
        return agent(Duration.ofMinutes(1));
    }

    private OrgAssistantAgent agent(Duration timeBudget) throws Exception {
        return new OrgAssistantAgent(model, new OrgTools(employees, departments, offices, projects, staffing),
                conversations, timeBudget);
    }

    private String ask(OrgAssistantAgent agent, AIRequest request) {
        return agent.ask(request, EMPLOYEE).answer();
    }

    @Test
    void answersDirectlyWhenNoLookupIsNeeded() throws Exception {
        model.then(request -> AiMessage.from("Hello! Ask me about the organisation."));

        assertThat(ask(agent(), question(null, "Hi"))).isEqualTo("Hello! Ask me about the organisation.");

        ChatRequest first = model.requests.get(0);
        assertThat(first.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) first.messages().get(1)).singleText()).isEqualTo("Hi");
        assertThat(first.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("searchEmployees", "getEmployee", "getReportingChain",
                        "getDirectReports", "getAllReports", "findDepartments", "getDepartmentMembers",
                        "findOffices", "findProjects", "getProjectMembers", "getEmployeeProjects",
                        "findCandidatesForProject", "findSkills", "getStaffingRequests");

        // The caller is passed in by the agent, never offered to the model as an argument.
        ToolSpecification staffingTool = first.toolSpecifications().stream()
                .filter(tool -> tool.name().equals("getStaffingRequests")).findFirst().orElseThrow();
        assertThat(staffingTool.parameters().properties()).containsOnlyKeys("projectId", "status");
    }

    @Test
    void runsRequestedToolsAndFeedsResultsBackToTheModel() throws Exception {
        when(employees.reportingChain("dev-1")).thenReturn(List.of(
                new EmployeeSummary("m-1", "Lena Lead", "Team Lead"),
                new EmployeeSummary("c-1", "Chitra CTO", "CTO")));

        model.then(request -> callTool("call-1", "getReportingChain", "{\"employeeId\":\"dev-1\"}"))
                .then(request -> AiMessage.from("Dev reports to Lena Lead, who reports to Chitra CTO."));

        String answer = ask(agent(), question("Dev", "Who is Dev's manager chain?"));

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

        assertThat(ask(agent(), question(null, "q"))).isEqualTo("done");

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

        assertThat(ask(agent(), question(null, "Who is nope?"))).isEqualTo("I couldn't find that employee.");

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

        assertThat(ask(agent(), question(null, "q"))).isEqualTo("answer");
        assertThat(model.requests).hasSize(3);
        assertThat(toolResults(model.requests.get(2))).hasSize(2);
    }

    @Test
    void stopsAfterTheToolRoundLimit() throws Exception {
        when(employees.reportingChain(any())).thenReturn(List.of());
        model.always(request -> callTool("x", "getReportingChain", "{\"employeeId\":\"loop\"}"));

        assertThat(ask(agent(), question(null, "loop forever"))).isEqualTo(OrgAssistantAgent.STEP_LIMIT_ANSWER);

        // MAX_TOOL_ROUNDS rounds of tools, plus the final model call that was stopped.
        assertThat(model.requests).hasSize(OrgAssistantAgent.MAX_TOOL_ROUNDS + 1);
    }

    @Test
    void eachQuestionStartsWithAFreshConversation() throws Exception {
        model.always(request -> AiMessage.from("ok"));
        OrgAssistantAgent agent = agent();

        ask(agent, question(null, "first"));
        ask(agent, question(null, "second"));

        List<ChatMessage> secondConversation = model.requests.get(1).messages();
        assertThat(secondConversation).hasSize(2);
        assertThat(((UserMessage) secondConversation.get(1)).singleText()).isEqualTo("second");
    }

    // ---- Follow-up questions ------------------------------------------------------

    @Test
    void followUpQuestionsSeeEarlierQuestionsAndAnswers() throws Exception {
        model.then(request -> AiMessage.from("Dev Kumar is an engineer."))
                .then(request -> AiMessage.from("Dev's manager is Chitra CTO."));
        OrgAssistantAgent agent = agent();

        AIResponse first = agent.ask(question(null, "Who is Dev?"), EMPLOYEE);
        AIResponse second = agent.ask(followUp(first.conversationId(), "Who is their manager?"), EMPLOYEE);

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(second.answer()).isEqualTo("Dev's manager is Chitra CTO.");

        // System prompt, earlier question, earlier answer, new question.
        List<ChatMessage> sent = model.requests.get(1).messages();
        assertThat(sent).hasSize(4);
        assertThat(((UserMessage) sent.get(1)).singleText()).isEqualTo("Who is Dev?");
        assertThat(((AiMessage) sent.get(2)).text()).isEqualTo("Dev Kumar is an engineer.");
        assertThat(((UserMessage) sent.get(3)).singleText()).isEqualTo("Who is their manager?");
    }

    @Test
    void anotherUserCannotContinueSomeoneElsesConversation() throws Exception {
        model.always(request -> AiMessage.from("ok"));
        OrgAssistantAgent agent = agent();

        AIResponse mine = agent.ask(question(null, "Something private"), EMPLOYEE);
        AIResponse theirs = agent.ask(followUp(mine.conversationId(), "What did they ask?"), HR);

        assertThat(theirs.conversationId()).isNotEqualTo(mine.conversationId());
        assertThat(model.requests.get(1).messages()).hasSize(2);
    }

    @Test
    void unknownConversationStartsANewOne() throws Exception {
        model.always(request -> AiMessage.from("ok"));

        AIResponse response = agent().ask(followUp("expired-or-made-up", "Hi"), EMPLOYEE);

        assertThat(response.conversationId()).isNotBlank().isNotEqualTo("expired-or-made-up");
        assertThat(model.requests.get(0).messages()).hasSize(2);
    }

    @Test
    void theToolRoundLimitIsPerQuestionNotPerConversation() throws Exception {
        when(employees.reportingChain(any())).thenReturn(List.of());
        OrgAssistantAgent agent = agent();

        // A first question that uses every allowed round...
        for (int i = 0; i < OrgAssistantAgent.MAX_TOOL_ROUNDS; i++) {
            model.then(request -> callTool("x", "getReportingChain", "{\"employeeId\":\"e\"}"));
        }
        model.then(request -> AiMessage.from("first answer"));
        AIResponse first = agent.ask(question(null, "first"), EMPLOYEE);
        assertThat(first.answer()).isEqualTo("first answer");

        // ...doesn't use up the follow-up's rounds.
        model.then(request -> callTool("y", "getReportingChain", "{\"employeeId\":\"e\"}"))
                .then(request -> AiMessage.from("second answer"));
        assertThat(agent.ask(followUp(first.conversationId(), "second"), EMPLOYEE).answer())
                .isEqualTo("second answer");
    }

    @Test
    void stopsWhenTheTimeBudgetIsUsedUp() throws Exception {
        when(employees.reportingChain(any())).thenReturn(List.of());
        model.always(request -> callTool("x", "getReportingChain", "{\"employeeId\":\"e\"}"));

        assertThat(ask(agent(Duration.ofMillis(-1)), question(null, "slow")))
                .isEqualTo(OrgAssistantAgent.TIME_LIMIT_ANSWER);
        assertThat(model.requests).hasSize(1);
    }

    // ---- Skills and staffing tools -----------------------------------------------

    @Test
    void findsSkillsByPartOfTheirName() throws Exception {
        when(employees.skills(eq("kube"), any())).thenReturn(new PageImpl<>(
                List.of(new SkillCount("Kubernetes", 3)), PageRequest.of(0, 50), 1));

        model.then(request -> callTool("s", "findSkills", "{\"name\":\"kube\"}"))
                .then(request -> AiMessage.from("done"));
        ask(agent(), question(null, "Who knows kube?"));

        assertThat(toolResults(model.requests.get(1)).get(0).text())
                .contains("\"name\":\"Kubernetes\"").contains("\"employees\":3");
    }

    @Test
    void hrCanSeeAllStaffingRequests() throws Exception {
        when(staffing.search(null, StaffingRequestStatus.PENDING, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(staffingRequest()), PageRequest.of(0, 50), 1));

        model.then(request -> callTool("r", "getStaffingRequests", "{\"status\":\"pending\"}"))
                .then(request -> AiMessage.from("done"));
        agent().ask(question(null, "What is waiting for approval?"), HR);

        assertThat(toolResults(model.requests.get(1)).get(0).text())
                .contains("\"total\":1").contains("Dev Kumar");
    }

    @Test
    void projectLeadsSeeOnlyTheirOwnProjectsStaffingRequests() throws Exception {
        when(staffing.isProjectLead("emma", "mine")).thenReturn(true);
        when(staffing.search(eq("mine"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(staffingRequest()), PageRequest.of(0, 50), 1));

        model.then(request -> AiMessage.from(
                        ToolExecutionRequest.builder().id("a").name("getStaffingRequests")
                                .arguments("{\"projectId\":\"mine\"}").build(),
                        ToolExecutionRequest.builder().id("b").name("getStaffingRequests")
                                .arguments("{\"projectId\":\"other\"}").build(),
                        ToolExecutionRequest.builder().id("c").name("getStaffingRequests")
                                .arguments("{}").build()))
                .then(request -> AiMessage.from("done"));
        ask(agent(), question(null, "Staffing requests?"));

        List<ToolExecutionResultMessage> results = toolResults(model.requests.get(1));
        assertThat(results.get(0).text()).contains("Dev Kumar");
        assertThat(results.get(1).text()).startsWith("Error:").contains("projects you lead");
        assertThat(results.get(2).text()).startsWith("Error:").contains("projects you lead");
        verify(staffing, never()).search(eq("other"), any(), any());
        verify(staffing, never()).search(eq(null), any(), any());
    }

    private static AIRequest followUp(String conversationId, String text) {
        AIRequest request = question(null, text);
        request.setConversationId(conversationId);
        return request;
    }

    private static StaffingRequestResponse staffingRequest() {
        return new StaffingRequestResponse("r-1", new ProjectSummary("p-1", "APOLLO", "Apollo", ProjectStatus.ACTIVE),
                new EmployeeSummary("d-1", "Dev Kumar", "Engineer"), StaffingAction.ASSIGN, "Developer", 50,
                StaffingRequestStatus.PENDING, "lena", Instant.parse("2026-09-01T10:00:00Z"), null, null, null);
    }
}
