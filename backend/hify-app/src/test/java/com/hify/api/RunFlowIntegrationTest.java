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
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "hify.run-timeout=5s"
})
class RunFlowIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;

    @Test
    void createsIdempotentRunAndPersistsToolEvents() throws Exception {
        String health = http.perform(get("/api/v1/health"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(health).path("code").asInt()).isEqualTo(200);
        assertThat(json(health).path("data").asText()).isEqualTo("Hify is running");

        String removedEndpoint = http.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"legacy\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(json(removedEndpoint).path("code").asInt()).isEqualTo(40400);

        JsonNode conversation = json(http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"demo-agent\",\"title\":\"Integration test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String conversationId = conversation.path("id").asText();

        String body = "{\"message\":\"计算 17 * 23\"}";
        String first = http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = json(first).path("id").asText();

        String replay = http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json(replay).path("id").asText()).isEqualTo(runId);

        String conflict = http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "integration-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"different body\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(json(conflict).path("code").asInt()).isEqualTo(40901);

        JsonNode run = awaitTerminal(runId);
        assertThat(run.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("outputMessage").asText()).contains("391");
        assertThat(run.path("toolCalls").asInt()).isEqualTo(1);

        JsonNode events = json(http.perform(get("/api/v1/runs/{id}/events", runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(events.toString()).contains("tool.call.started", "tool.call.completed", "run.completed");
    }

    private JsonNode awaitTerminal(String runId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            JsonNode run = json(http.perform(get("/api/v1/runs/{id}", runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (!"RUNNING".equals(run.path("state").asText())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not reach a terminal state");
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
