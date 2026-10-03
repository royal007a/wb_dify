package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.agent.api.AgentQueryService;
import com.hify.common.*;
import com.hify.mcp.application.McpCredentialResolver;
import com.hify.runtime.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
abstract class AbstractMcpCredentialContract {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired McpCredentialResolver resolver;
    @Autowired AgentQueryService agents;
    @Autowired ToolRuntime runtime;
    HttpServer remote;
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicInteger requests = new AtomicInteger();
    String endpoint;
    @BeforeEach void serve() throws Exception {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = "http://127.0.0.1:" + remote.getAddress().getPort() + "/mcp";
        remote.createContext("/mcp", x -> {
            requests.incrementAndGet();
            authorization.set(x.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = json.readTree(x.getRequestBody());
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) { x.sendResponseHeaders(202, -1); x.close(); return; }
            String response = switch (method) {
                case "initialize" -> "{\"result\":{\"protocolVersion\":\"2025-06-18\"}}";
                case "tools/list" -> "{\"result\":{\"tools\":[{\"name\":\"lookup\",\"description\":\"Lookup\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}}]}}";
                default -> json.createObjectNode().set("result", json.createObjectNode().put("isError", false)
                        .put("text", "safe-result " + authorization.get())).toString();
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        remote.start();
    }
    @AfterEach void stop() { remote.stop(0); }
    ObjectNode input() { return json.createObjectNode().put("name", "credential-" + UUID.randomUUID()).put("endpointUrl", endpoint).put("enabled", true); }
    String create(ObjectNode body) throws Exception {
        return json.readTree(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
    }
    JsonNode getServer(String id) throws Exception {
        return json.readTree(http.perform(get("/api/v1/mcp-servers/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    void update(String id, ObjectNode body) throws Exception {
        http.perform(put("/api/v1/mcp-servers/{id}", id).contentType("application/json").content(body.toString())).andExpect(status().isOk());
    }
    String reference(String id) { return jdbc.queryForObject("select credential_ref from mcp_servers where id=?", String.class, id); }

    @Test void tokenIsEncryptedNeverReturnedAndKeepReplaceClearAreExplicit() throws Exception {
        var body = input().put("credentialAction", "TOKEN").put("credentialToken", "fake-token-one");
        String id = create(body);
        String first = reference(id);
        assertThat(first).startsWith("stored:");
        String encrypted = jdbc.queryForObject("select encrypted_value from mcp_credentials where server_id=?", String.class, id);
        assertThat(encrypted).startsWith("v1:").doesNotContain("fake-token-one");
        JsonNode view = getServer(id);
        assertThat(view.path("credentialMode").asText()).isEqualTo("TOKEN");
        assertThat(view.path("credentialConfigured").asBoolean()).isTrue();
        assertThat(view.path("credentialRef").isNull()).isTrue();
        assertThat(view.toString()).doesNotContain("fake-token-one", "stored:", encrypted);
        String list = http.perform(get("/api/v1/mcp-servers")).andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("fake-token-one", "stored:", encrypted);
        body.remove("credentialToken"); body.put("credentialAction", "KEEP"); update(id, body);
        assertThat(reference(id)).isEqualTo(first);
        body.remove("credentialAction"); update(id, body); // old client omitting credentials is safe
        assertThat(reference(id)).isEqualTo(first);
        body.put("credentialAction", "TOKEN").put("credentialToken", "fake-token-two"); update(id, body);
        String second = reference(id);
        assertThat(second).isNotEqualTo(first);
        assertThat(resolver.resolve(id, first)).isEqualTo("fake-token-one");
        assertThat(resolver.resolve(id, second)).isEqualTo("fake-token-two");
        body.remove("credentialToken"); body.put("credentialAction", "CLEAR"); update(id, body);
        assertThat(reference(id)).isNull();
        assertThat(getServer(id).path("credentialConfigured").asBoolean()).isFalse();
        assertThat(resolver.resolve(id, first)).isEqualTo("fake-token-one"); // historical version retained
    }

    @Test void frozenAgentVersionKeepsOldAuthorizationAndRemoteEchoIsRedacted() throws Exception {
        var body = input().put("credentialAction", "TOKEN").put("credentialToken", "fake-old-version-token");
        String id = create(body);
        http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh", id)).andExpect(status().isOk());
        assertThat(authorization.get()).isEqualTo("Bearer fake-old-version-token");
        var agent = json.createObjectNode().put("name", "Credential Agent " + UUID.randomUUID()).put("instructions", "Use lookup")
                .put("providerId", "mock").put("modelId", "hify-mock").put("temperature", 0.2)
                .put("maxTokens", 1024).put("maxTurns", 4).put("maxContextTurns", 10).put("enabled", true);
        agent.putArray("enabledTools");
        String agentId = json.readTree(http.perform(post("/api/v1/agents").contentType("application/json").content(agent.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        http.perform(put("/api/v1/agents/{id}/mcp-bindings", agentId).contentType("application/json")
                .content(json.writeValueAsString(Map.of("bindings", List.of(Map.of("serverId", id, "toolNames", List.of("lookup"))))))).andExpect(status().isOk());
        String version = json.readTree(http.perform(post("/api/v1/agents/{id}/publications", agentId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        body.put("credentialToken", "fake-new-version-token"); update(id, body);
        assertThat(getServer(id).path("status").asText()).isEqualTo("NEW");
        http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh", id)).andExpect(status().isOk());
        assertThat(authorization.get()).isEqualTo("Bearer fake-new-version-token");
        var tool = agents.requireVersion(version).mcpTools().get(0);
        var definition = new ToolDefinition(tool.runtimeToolName(), tool.description(), tool.inputSchema(), "read");
        var capability = runtime.snapshot(version, Set.of(tool.runtimeToolName()), List.of(definition));
        var result = runtime.execute(new RuntimeMessage.ToolCall("frozen", tool.runtimeToolName(), Map.of()), capability,
                ToolExecutionLease.local("frozen", capability.revision()), ExecutionControl.none());
        assertThat(result.error()).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer fake-old-version-token");
        assertThat(result.value().toString()).contains("safe-result", "[REDACTED]").doesNotContain("fake-old-version-token");
        body.remove("credentialToken"); body.put("credentialAction", "CLEAR"); update(id, body);
        http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh", id)).andExpect(status().isOk());
        assertThat(authorization.get()).isNull();
    }

    @Test void rejectsCrossServerReferencesAndMixedOperationsWithoutLeakingInputs(CapturedOutput logs) throws Exception {
        String id = create(input().put("credentialAction", "TOKEN").put("credentialToken", "fake-scoped-token"));
        String ref = reference(id);
        assertThatThrownBy(() -> resolver.resolve("other-server", ref)).isInstanceOf(BizException.class);
        for (ObjectNode body : List.of(
                input().put("credentialRef", ref),
                input().put("credentialAction", "KEEP").put("credentialToken", "fake-scoped-token"),
                input().put("credentialAction", "CLEAR").put("credentialToken", "fake-scoped-token"),
                input().put("credentialAction", "TOKEN").put("credentialRef", "env:X").put("credentialToken", "fake-scoped-token"),
                input().put("credentialAction", "TOKEN").put("credentialToken", "Bearer fake-scoped-token"),
                input().put("credentialAction", "TOKEN").put("credentialToken", "fake-scoped-token\r\nX: x"),
                input().put("credentialAction", "TOKEN").put("credentialToken", ""),
                input().put("credentialAction", "TOKEN"),
                input().put("credentialToken", "fake-scoped-token"))) {
            String error = http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(body.toString()))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(error).doesNotContain("fake-scoped-token", ref);
        }
        var malformed = input(); malformed.putObject("credentialToken").put("secret", "fake-scoped-token");
        String error = http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(malformed.toString()))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(error).doesNotContain("fake-scoped-token");
        assertThat(logs.getAll()).doesNotContain("fake-scoped-token");
    }

    @Test void legacyPlaintextIsNotProjectedToBrowser() throws Exception {
        String id = create(input());
        jdbc.update("update mcp_servers set credential_ref=? where id=?", "legacy-fake-raw-token", id);
        JsonNode view = getServer(id);
        assertThat(view.path("credentialMode").asText()).isEqualTo("UNAVAILABLE");
        assertThat(view.toString()).doesNotContain("legacy-fake-raw-token");
    }

    @Test void rejectsProcessSecretReferencesAtSaveWithoutReadingTheirValues() throws Exception {
        for (String ref : List.of("env:HIFY_MCP_MASTER_KEY", "env:SPRING_DATASOURCE_PASSWORD",
                "env:HIFY_DB_PASSWORD", "env:HIFY_DB_URL", "env:HIFY_DB_USERNAME", "system:javax.net.ssl.keyStorePassword",
                "env:DB_PASSWORD", "system:hify.mcp.credentials.master-key", "system:hify.review.unlisted")) {
            http.perform(post("/api/v1/mcp-servers").contentType("application/json")
                    .content(input().put("credentialAction", "REFERENCE").put("credentialRef", ref).toString()))
                    .andExpect(status().isBadRequest());
        }
        assertThat(authorization.get()).isNull();
    }

    @Test void legacyUnlistedReferenceIsRejectedBeforeSendingAnyHttp() throws Exception {
        String id = create(input());
        // Fake property only. Do not resolve an actual process secret even on the red run.
        System.setProperty("hify.review.unlisted", "fake-not-a-real-process-secret");
        try {
            jdbc.update("update mcp_servers set credential_ref=? where id=?", "system:hify.review.unlisted", id);
            String result = http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh", id))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(result).doesNotContain("fake-not-a-real-process-secret");
            assertThat(authorization.get()).isNull();
        } finally { System.clearProperty("hify.review.unlisted"); }
    }

    @Test void endpointChangeCannotKeepStoredCredentialImplicitly() throws Exception {
        var body = input().put("credentialAction", "TOKEN").put("credentialToken", "fake-bound-endpoint-token");
        String id = create(body);
        String originalRef = reference(id);
        body.remove("credentialToken");
        body.put("endpointUrl", endpoint + "/other").put("credentialAction", "KEEP");
        http.perform(put("/api/v1/mcp-servers/{id}", id).contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest());
        assertThat(getServer(id).path("endpointUrl").asText()).isEqualTo(endpoint);
        assertThat(reference(id)).isEqualTo(originalRef);
        body.remove("credentialAction");
        http.perform(put("/api/v1/mcp-servers/{id}", id).contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest());
        body.put("credentialAction", "CLEAR");
        update(id, body);
        assertThat(reference(id)).isNull();
    }

    @Test void frozenPublishedRevisionWithUnapprovedLegacyReferenceSendsNoHttp(CapturedOutput logs) throws Exception {
        String id = create(input());
        http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh", id)).andExpect(status().isOk());
        var agent = json.createObjectNode().put("name", "Legacy Credential Agent " + UUID.randomUUID()).put("instructions", "Use lookup")
                .put("providerId", "mock").put("modelId", "hify-mock").put("temperature", 0.2)
                .put("maxTokens", 1024).put("maxTurns", 4).put("maxContextTurns", 10).put("enabled", true);
        agent.putArray("enabledTools");
        String agentId = json.readTree(http.perform(post("/api/v1/agents").contentType("application/json").content(agent.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        http.perform(put("/api/v1/agents/{id}/mcp-bindings", agentId).contentType("application/json")
                .content(json.writeValueAsString(Map.of("bindings", List.of(Map.of("serverId", id, "toolNames", List.of("lookup")))))))
                .andExpect(status().isOk());
        String version = json.readTree(http.perform(post("/api/v1/agents/{id}/publications", agentId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        var tool = agents.requireVersion(version).mcpTools().get(0);
        var definition = new ToolDefinition(tool.runtimeToolName(), tool.description(), tool.inputSchema(), "read");
        var capability = runtime.snapshot(version, Set.of(tool.runtimeToolName()), List.of(definition));
        // Simulate a pre-policy persisted revision. Production revisions are never edited this way.
        // Leave the draft credential empty: runtime must validate the frozen reference, not the draft.
        jdbc.update("update mcp_server_revisions set credential_ref=? where server_id=? and server_revision=?",
                "system:hify.review.frozen-unlisted", id, tool.serverRevision());
        int before = requests.get();
        System.setProperty("hify.review.frozen-unlisted", "fake-frozen-only-secret");
        try {
            var result = runtime.execute(new RuntimeMessage.ToolCall("legacy-frozen", tool.runtimeToolName(), Map.of()), capability,
                    ToolExecutionLease.local("legacy-frozen", capability.revision()), ExecutionControl.none());
            assertThat(result.error()).isTrue();
            assertThat(result.toString()).contains("not approved").doesNotContain("fake-frozen-only-secret");
            assertThat(requests).hasValue(before);
            assertThat(reference(id)).isNull();
            assertThat(logs.getAll()).doesNotContain("fake-frozen-only-secret");
        } finally { System.clearProperty("hify.review.frozen-unlisted"); }
    }
}
