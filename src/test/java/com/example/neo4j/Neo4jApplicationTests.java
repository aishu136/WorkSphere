package com.example.neo4j;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.neo4j.support.EmbeddedNeo4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Starts the whole application (security, migrations, admin bootstrap, Camel, AI beans)
 * against an embedded Neo4j and drives a small org-chart scenario over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Neo4jApplicationTests {

	private static final Neo4j neo4j = EmbeddedNeo4j.start();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		EmbeddedNeo4j.register(registry, neo4j);
		registry.add("app.jwt.secret", () -> "test-secret-that-is-at-least-32-bytes-long");
		registry.add("app.bootstrap.admin-username", () -> "admin");
		registry.add("app.bootstrap.admin-password", () -> "bootstrap-password");
	}

	@AfterAll
	static void stopNeo4j() {
		neo4j.close();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	// Stands in for Bedrock so the LangGraph4j assistant can be driven end to end.
	@MockitoBean
	ChatModel chatModel;

	private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
			throws Exception {
		String body = mvc.perform(request)
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
		return body.isEmpty() ? null : json.readTree(body);
	}

	// The model calls one tool, then answers with that tool's raw result.
	private void answerWithToolResult(String tool, String argumentsJson) {
		org.mockito.Mockito.doAnswer(invocation -> {
					List<ChatMessage> messages = ((ChatRequest) invocation.getArgument(0)).messages();
					ChatMessage last = messages.get(messages.size() - 1);
					AiMessage reply = last instanceof ToolExecutionResultMessage result
							? AiMessage.from(result.text())
							: AiMessage.from(ToolExecutionRequest.builder().id("t").name(tool)
									.arguments(argumentsJson).build());
					return ChatResponse.builder().aiMessage(reply).build();
				})
				.when(chatModel).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class));
	}

	@Test
	void theWebAppLoadsWithoutLoggingInButTheApiDoesNot() throws Exception {

		mvc.perform(get("/")).andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("index.html"));
		mvc.perform(get("/index.html")).andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.string(org.hamcrest.Matchers.containsString("app.js")));
		mvc.perform(get("/app.js")).andExpect(status().isOk());
		mvc.perform(get("/app.css")).andExpect(status().isOk());
		mvc.perform(get("/employees")).andExpect(status().isUnauthorized());

		// The pages behind it answer for a logged-in admin.
		String auth = "Bearer " + call(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"bootstrap-password\"}"), 200)
				.get("accessToken").asText();
		String id = call(post("/employees").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("{\"employeeCode\":\"WEB1\",\"name\":\"Wendy Web\",\"email\":\"wendy@example.com\","
						+ "\"hireDate\":\"2024-01-15\"}"), 201).get("id").asText();

		mvc.perform(get("/analytics/overview").header("Authorization", auth))
				.andExpect(status().isOk()).andExpect(jsonPath("$.headcount").isNumber());
		mvc.perform(get("/analytics/managers").header("Authorization", auth)).andExpect(status().isOk());
		mvc.perform(get("/analytics/project-skill-risks").header("Authorization", auth)).andExpect(status().isOk());
		mvc.perform(get("/analytics/skill-gaps").header("Authorization", auth)).andExpect(status().isOk());
		mvc.perform(get("/employees/" + id + "/reporting-history").header("Authorization", auth))
				.andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value(id));
		mvc.perform(get("/employees/" + id + "/reports-on").param("date", "2026-01-01").header("Authorization", auth))
				.andExpect(status().isOk());
	}

	@Test
	void adminCanBuildAndReadAnOrgChart() throws Exception {

		String token = call(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"bootstrap-password\"}"), 200)
				.get("accessToken").asText();
		String auth = "Bearer " + token;

		String engId = call(post("/departments").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"ENG\",\"name\":\"Engineering\"}"), 201).get("id").asText();

		String cto = call(post("/employees").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"employeeCode":"E1","name":"Chitra CTO","email":"chitra@example.com",
						 "jobTitle":"CTO","hireDate":"2020-02-01","departmentId":"%s"}
						""".formatted(engId)), 201).get("id").asText();

		String dev = call(post("/employees").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"employeeCode":"E2","name":"Dev Kumar","email":"dev@example.com",
						 "hireDate":"2024-06-01","departmentId":"%s","managerId":"%s"}
						""".formatted(engId, cto)), 201).get("id").asText();

		call(put("/departments/" + engId + "/head/" + cto).header("Authorization", auth), 200);

		// A cycle is rejected end to end.
		mvc.perform(put("/employees/" + cto + "/manager/" + dev).header("Authorization", auth))
				.andExpect(status().isConflict());

		mvc.perform(get("/employees").param("name", "dev").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].manager.name").value("Chitra CTO"))
				.andExpect(jsonPath("$.content[0].department.code").value("ENG"));

		mvc.perform(get("/departments/" + engId).header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.head.name").value("Chitra CTO"))
				.andExpect(jsonPath("$.memberCount").value(2));

		mvc.perform(get("/employees/" + dev + "/reporting-chain").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Chitra CTO"));

		// ---- Manager self-service: a plain EMPLOYEE login linked to the CTO ----

		call(post("/users").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"username":"chitra","password":"chitra-password-123","roles":["EMPLOYEE"],"employeeId":"%s"}
						""".formatted(cto)), 201);

		String managerAuth = "Bearer " + call(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"chitra\",\"password\":\"chitra-password-123\"}"), 200)
				.get("accessToken").asText();

		mvc.perform(get("/employees/me").header("Authorization", managerAuth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(cto));

		// Own report: allowed.
		mvc.perform(put("/employees/" + dev + "/status").header("Authorization", managerAuth)
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ON_LEAVE"));

		// Themselves, and HR-only profile edits: forbidden.
		mvc.perform(put("/employees/" + cto + "/status").header("Authorization", managerAuth)
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_LEAVE\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(put("/employees/" + dev).header("Authorization", managerAuth)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Renamed\",\"email\":\"dev@example.com\",\"hireDate\":\"2024-06-01\"}"))
				.andExpect(status().isForbidden());

		// The manager's change queued an email to the employee (no HR mailbox is configured here).
		mvc.perform(get("/outbox").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].recipient").value("dev@example.com"))
				.andExpect(jsonPath("$.content[0].subject").value("[WorkSphere] Your status is now: On leave"));

		// ---- Audit trail ----

		mvc.perform(get("/employees/" + dev + "/history").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].action").value("EMPLOYEE_STATUS_CHANGED"))
				.andExpect(jsonPath("$.content[0].actor").value("chitra"))
				.andExpect(jsonPath("$.content[0].details.status.from").value("ACTIVE"))
				.andExpect(jsonPath("$.content[0].details.status.to").value("ON_LEAVE"))
				// Oldest last: created by the admin, then placed in the department and under the CTO.
				.andExpect(jsonPath("$.content[3].action").value("EMPLOYEE_CREATED"))
				.andExpect(jsonPath("$.content[3].actor").value("admin"))
				.andExpect(jsonPath("$.totalElements").value(4));

		mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"chitra\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/audit").param("action", "LOGIN_FAILED").header("Authorization", auth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].actor").value("chitra"))
				.andExpect(jsonPath("$.content[0].details.reason").value("BAD_CREDENTIALS"));

		// ---- AI assistant: /ai -> Camel -> LangGraph4j graph -> tools -> Neo4j ----

		String devId = dev;
		org.mockito.Mockito.when(chatModel.chat(org.mockito.ArgumentMatchers.any(ChatRequest.class)))
				.thenAnswer(invocation -> {
					ChatRequest request = invocation.getArgument(0);
					List<ChatMessage> messages = request.messages();
					long toolResults = messages.stream().filter(ToolExecutionResultMessage.class::isInstance).count();
					AiMessage reply;
					if (toolResults == 0) {
						reply = AiMessage.from(ToolExecutionRequest.builder().id("t1").name("searchEmployees")
								.arguments("{\"name\":\"dev\"}").build());
					} else if (toolResults == 1) {
						reply = AiMessage.from(ToolExecutionRequest.builder().id("t2").name("getEmployee")
								.arguments("{\"employeeId\":\"" + devId + "\"}").build());
					} else {
						// Echo what the tools returned, to prove real data reached the model.
						reply = AiMessage.from(messages.stream()
								.filter(ToolExecutionResultMessage.class::isInstance)
								.map(m -> ((ToolExecutionResultMessage) m).text())
								.collect(java.util.stream.Collectors.joining(System.lineSeparator())));
					}
					return ChatResponse.builder().aiMessage(reply).build();
				});

		JsonNode aiResponse = call(post("/ai").header("Authorization", managerAuth)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"Who is Dev's manager and when did they join?\"}"), 200);

		org.assertj.core.api.Assertions.assertThat(aiResponse.get("answer").asText())
				.contains("Dev Kumar")
				.contains("Chitra CTO")
				.contains("2024-06-01")
				.contains("ON_LEAVE");
		org.assertj.core.api.Assertions.assertThat(aiResponse.get("fallback").asBoolean()).isFalse();
		String conversationId = aiResponse.get("conversationId").asText();

		// A follow-up in the same conversation: the model sees the earlier question and answer.
		// (doAnswer, because re-stubbing with when() would call the previous stub with a null request.)
		org.mockito.Mockito.doAnswer(invocation -> ChatResponse.builder()
						.aiMessage(AiMessage.from("messages seen: "
								+ ((ChatRequest) invocation.getArgument(0)).messages().size()))
						.build())
				.when(chatModel).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class));

		mvc.perform(post("/ai").header("Authorization", managerAuth).contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"And their team?\",\"conversationId\":\"%s\"}"
								.formatted(conversationId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.conversationId").value(conversationId))
				// System prompt, first question, first answer, follow-up.
				.andExpect(jsonPath("$.answer").value("messages seen: 4"));

		// Skills: part of a name finds the skill and how many people have it.
		call(post("/employees/" + dev + "/skills").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kubernetes\"}"), 200);
		answerWithToolResult("findSkills", "{\"name\":\"KUBE\"}");

		mvc.perform(post("/ai").header("Authorization", managerAuth).contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"Who knows kube?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString(
						"{\"name\":\"Kubernetes\",\"employees\":1}")));

		// Staffing requests are HR or project lead only; Chitra leads no project.
		answerWithToolResult("getStaffingRequests", "{}");

		mvc.perform(post("/ai").header("Authorization", managerAuth).contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"Which staffing requests are pending?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("projects you lead")));

		mvc.perform(post("/ai").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"Which staffing requests are pending?\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("\"total\":0")));

		// The model failing (e.g. Bedrock down) gives a stand-in answer, not a 500.
		org.mockito.Mockito.doThrow(new RuntimeException("Bedrock is down"))
				.when(chatModel).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class));

		mvc.perform(post("/ai").header("Authorization", managerAuth).contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"Anyone there?\",\"conversationId\":\"%s\"}"
								.formatted(conversationId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fallback").value(true))
				.andExpect(jsonPath("$.conversationId").value(conversationId))
				.andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("unavailable")));

		// The manager can't read the audit log or history.
		mvc.perform(get("/audit").header("Authorization", managerAuth)).andExpect(status().isForbidden());
		mvc.perform(get("/employees/" + dev + "/history").header("Authorization", managerAuth))
				.andExpect(status().isForbidden());
	}
}
