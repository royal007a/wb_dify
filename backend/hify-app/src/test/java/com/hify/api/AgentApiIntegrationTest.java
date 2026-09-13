package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-agent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class AgentApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;

    @Test
    void draftPublicationAndConversationUseImmutableVersion() throws Exception {
        String agentId = json(http.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("Version one", 0.2)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("data").asText();

        JsonNode v1 = json(http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(v1.path("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.path("snapshotDigest").asText()).hasSize(64);

        JsonNode conversation = json(http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"title\":\"Pinned\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(conversation.path("agentVersionId").asText()).isEqualTo(v1.path("id").asText());

        http.perform(put("/api/v1/agents/{id}", agentId)
                        .contentType(MediaType.APPLICATION_JSON).content(payload("Version two", 0.7)))
                .andExpect(status().isOk());
        JsonNode v2 = json(http.perform(post("/api/v1/agents/{id}/publications", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(v2.path("versionNo").asInt()).isEqualTo(2);
        assertThat(v2.path("snapshotDigest").asText()).isNotEqualTo(v1.path("snapshotDigest").asText());

        JsonNode run = json(http.perform(post("/api/v1/conversations/{id}/runs", conversation.path("id").asText())
                        .header("Idempotency-Key", "agent-version-pin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(run.path("agentVersionId").asText()).isEqualTo(v1.path("id").asText());
        assertThat(run.path("agentSnapshotDigest").asText()).isEqualTo(v1.path("snapshotDigest").asText());

        JsonNode versions = json(http.perform(get("/api/v1/agents/{id}/versions", agentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(versions).hasSize(2);
    }

    private String payload(String instructions, double temperature) {
        return """
                {"name":"Versioned Agent","description":"integration test","instructions":"%s",
                 "providerId":"mock","modelId":"hify-mock","temperature":%s,"maxTokens":2048,
                 "maxTurns":6,"maxContextTurns":10,"enabledTools":["calculator"],"enabled":true}
                """.formatted(instructions, temperature);
    }

    private JsonNode json(String value) throws Exception { return objectMapper.readTree(value); }
}
