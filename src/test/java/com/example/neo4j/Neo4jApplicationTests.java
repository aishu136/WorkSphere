package com.example.neo4j;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.neo4j.support.EmbeddedNeo4j;
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

	private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
			throws Exception {
		String body = mvc.perform(request)
				.andExpect(status().is(expectedStatus))
				.andReturn().getResponse().getContentAsString();
		return body.isEmpty() ? null : json.readTree(body);
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

		// The manager can't read the audit log or history.
		mvc.perform(get("/audit").header("Authorization", managerAuth)).andExpect(status().isForbidden());
		mvc.perform(get("/employees/" + dev + "/history").header("Authorization", managerAuth))
				.andExpect(status().isForbidden());
	}
}
