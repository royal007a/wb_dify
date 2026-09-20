package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.AgentQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-agent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class AgentApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentQueryService agents;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void draftPublicationAndConversationUseImmutableVersion() throws Exception {
        String agentId = json(http.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Versioned Agent", "Version one", 0.2, "calculator")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").asText();

        JsonNode v1 = json(http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(v1.path("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.path("snapshotDigest").asText()).hasSize(64);
        assertThat(agents.requireVersion(v1.path("id").asText()).enabledTools())
                .containsExactly("calculator");
        JsonNode synchronizedDraft = json(http.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(synchronizedDraft.path("hasUnpublishedChanges").asBoolean()).isFalse();
        jdbc.update("UPDATE agent_versions SET snapshot_digest = ? WHERE id = ?",
                "legacy-order-dependent-digest", v1.path("id").asText());
        JsonNode migratedDraft = json(http.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(migratedDraft.path("hasUnpublishedChanges").asBoolean()).isFalse();
        jdbc.update("UPDATE agent_versions SET snapshot_digest = ? WHERE id = ?",
                v1.path("snapshotDigest").asText(), v1.path("id").asText());

        JsonNode conversation = json(http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"title\":\"Pinned\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(conversation.path("agentVersionId").asText()).isEqualTo(v1.path("id").asText());

        http.perform(put("/api/v1/agents/{id}", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload("Versioned Agent", "Version two", 0.7)))
                .andExpect(status().isOk());
        http.perform(put("/api/v1/agents/{id}/tools", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[\"current_time\"]}"))
                .andExpect(status().isOk());
        JsonNode draft = json(http.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(draft.path("enabledTools").get(0).asText()).isEqualTo("current_time");
        assertThat(draft.path("hasUnpublishedChanges").asBoolean()).isTrue();
        JsonNode v2 = json(http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(v2.path("versionNo").asInt()).isEqualTo(2);
        assertThat(v2.path("snapshotDigest").asText()).isNotEqualTo(v1.path("snapshotDigest").asText());
        assertThat(agents.requireVersion(v1.path("id").asText()).enabledTools())
                .containsExactly("calculator");
        assertThat(agents.requireVersion(v2.path("id").asText()).enabledTools())
                .containsExactly("current_time");
        JsonNode republishedDraft = json(http.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(republishedDraft.path("hasUnpublishedChanges").asBoolean()).isFalse();

        JsonNode run = json(http.perform(post("/api/v1/conversations/{id}/runs", conversation.path("id").asText())
                        .header("Idempotency-Key", "agent-version-pin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(run.path("agentVersionId").asText()).isEqualTo(v1.path("id").asText());
        assertThat(run.path("agentSnapshotDigest").asText()).isEqualTo(v1.path("snapshotDigest").asText());

        JsonNode versions = json(http.perform(get("/api/v1/agents/{id}/versions", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(versions).hasSize(2);

        http.perform(delete("/api/v1/agents/{id}", agentId)).andExpect(status().isOk());
        JsonNode archivedRun = json(http.perform(post("/api/v1/conversations/{id}/runs",
                        conversation.path("id").asText())
                        .header("Idempotency-Key", "archived-agent-old-conversation")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"still pinned\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(archivedRun.path("agentVersionId").asText()).isEqualTo(v1.path("id").asText());
        http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"title\":\"new blocked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void archiveRemovesDraftBindingsButPreservesPublishedSnapshot() throws Exception {
        String name = "Archive Agent";
        String agentId = json(http.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(name, "archive", 0.3, "calculator")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").asText();
        JsonNode version = json(http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");

        http.perform(delete("/api/v1/agents/{id}", agentId)).andExpect(status().isOk());

        http.perform(get("/api/v1/agents/{id}", agentId)).andExpect(status().isNotFound());
        http.perform(put("/api/v1/agents/{id}", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload(name, "blocked", 0.3)))
                .andExpect(status().isNotFound());
        http.perform(put("/api/v1/agents/{id}/tools", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[\"current_time\"]}"))
                .andExpect(status().isNotFound());
        http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isNotFound());
        http.perform(delete("/api/v1/agents/{id}", agentId)).andExpect(status().isNotFound());
        http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"title\":\"blocked\"}"))
                .andExpect(status().isNotFound());
        assertThat(agents.requireVersion(version.path("id").asText()).enabledTools())
                .containsExactly("calculator");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_bindings WHERE agent_id = ?",
                Long.class, agentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_version_tool_bindings WHERE agent_version_id = ?",
                Long.class, version.path("id").asText())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_definitions WHERE id = ? AND archived_at IS NOT NULL",
                Long.class, agentId)).isEqualTo(1L);

        http.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(name, "same archived name", 0.3, "calculator")))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesParametersAndToolBindings() throws Exception {
        http.perform(get("/api/v1/agents?page=0&pageSize=20"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/v1/agents?page=1&pageSize=101"))
                .andExpect(status().isBadRequest());

        http.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Too Hot Agent", "invalid temperature", 1.1, "calculator")))
                .andExpect(status().isBadRequest());

        http.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Unknown Tool Agent", "invalid tool", 0.4, "missing_tool")))
                .andExpect(status().isBadRequest());

        String agentId = json(http.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Binding Validation Agent", "valid", 0.4, "calculator")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").asText();
        JsonNode unpublished = json(http.perform(get("/api/v1/agents/{id}", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(unpublished.path("publishedVersionNo").isNull()).isTrue();
        http.perform(put("/api/v1/agents/{id}/tools", agentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolIds\":[\"calculator\",\"calculator\"]}"))
                .andExpect(status().isBadRequest());

        try {
            jdbc.update("UPDATE providers SET enabled = FALSE WHERE public_id = 'mock'");
            http.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                            .content(payload("Disabled Provider Agent", "invalid provider", 0.4)))
                    .andExpect(status().isConflict());
        } finally {
            jdbc.update("UPDATE providers SET enabled = TRUE WHERE public_id = 'mock'");
        }

        try {
            jdbc.update("""
                    UPDATE provider_models SET enabled = FALSE
                    WHERE provider_id = (SELECT id FROM providers WHERE public_id = 'mock')
                      AND model_id = 'hify-mock'
                    """);
            http.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON)
                            .content(payload("Disabled Model Agent", "invalid model", 0.4)))
                    .andExpect(status().isBadRequest());
        } finally {
            jdbc.update("""
                    UPDATE provider_models SET enabled = TRUE
                    WHERE provider_id = (SELECT id FROM providers WHERE public_id = 'mock')
                      AND model_id = 'hify-mock'
                    """);
        }
    }

    @Test
    void exposesStableReadOnlyToolCatalog() throws Exception {
        JsonNode catalog = json(http.perform(get("/api/v1/tools"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");

        assertThat(catalog).hasSize(4);
        assertThat(catalog.findValuesAsText("id")).containsExactly(
                "current_time", "calculator", "history.search", "history.detail");
        assertThat(catalog.findValuesAsText("displayName")).containsExactly(
                "当前时间", "计算器", "历史搜索", "历史详情");
        assertThat(catalog.findValuesAsText("source")).containsOnly("BUILTIN");
        assertThat(catalog.findValuesAsText("risk")).containsOnly("READ");
        assertThat(catalog.findValues("available")).allMatch(JsonNode::asBoolean);
    }

    @Test
    void listUsesBoundedBatchQueries() throws Exception {
        for (int index = 1; index <= 3; index++) {
            String agentId = json(http.perform(post("/api/v1/agents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload("Batch Agent " + index, "batch", 0.4,
                                    index % 2 == 0 ? "current_time" : "calculator")))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                    .path("data").asText();
            if (index <= 2) {
                http.perform(post("/api/v1/agents/{id}/publications", agentId))
                        .andExpect(status().isOk());
            }
        }

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        JsonNode page = json(http.perform(get("/api/v1/agents?page=1&pageSize=100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(page.path("data").size()).isGreaterThanOrEqualTo(3);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    private String payload(String name, String instructions, double temperature, String... tools) {
        String toolJson = java.util.Arrays.stream(tools)
                .map(tool -> "\"" + tool + "\"").collect(java.util.stream.Collectors.joining(","));
        return """
                {"name":"%s","description":"integration test","instructions":"%s",
                 "providerId":"mock","modelId":"hify-mock","temperature":%s,"maxTokens":2048,
                 "maxTurns":6,"maxContextTurns":10,"enabledTools":[%s],"enabled":true}
                """.formatted(name, instructions, temperature, toolJson);
    }

    private String updatePayload(String name, String instructions, double temperature) {
        return """
                {"name":"%s","description":"integration test","instructions":"%s",
                 "providerId":"mock","modelId":"hify-mock","temperature":%s,"maxTokens":2048,
                 "maxTurns":6,"maxContextTurns":10,"enabled":true}
                """.formatted(name, instructions, temperature);
    }

    private JsonNode json(String value) throws Exception { return objectMapper.readTree(value); }
}
