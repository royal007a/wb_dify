package com.hify.api;

import com.fasterxml.jackson.databind.*;
import com.hify.knowledge.application.KnowledgeRetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:hify-workflow-control;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password=", "hify.run-timeout=120s"})
class WorkflowRunControlIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @SpyBean KnowledgeRetrievalService knowledge;

    @Test void publicCancellationStopsBlockedWorkflowAndProducesOnlyCancelledRun() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String kb = body(http.perform(post("/api/v1/knowledge-bases").contentType("application/json")
                .content(json.writeValueAsString(Map.of("name","cancel-kb-"+suffix,"chunkSize",64,"chunkOverlap",8))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        String workflow = """
                {"name":"cancel-%s","schemaVersion":1,"nodes":[
                  {"nodeKey":"start","type":"START","name":"start","config":{}},
                  {"nodeKey":"lookup","type":"KNOWLEDGE","name":"lookup","config":{"knowledgeBaseId":"%s","query":"{{start.userMessage}}"}},
                  {"nodeKey":"end","type":"END","name":"end","config":{"output":"must not be committed"}}
                ],"edges":[{"edgeKey":"first","sourceNodeKey":"start","targetNodeKey":"lookup"},
                            {"edgeKey":"last","sourceNodeKey":"lookup","targetNodeKey":"end"}]}
                """.formatted(suffix,kb);
        String wid = body(http.perform(post("/api/v1/workflows").contentType("application/json").content(workflow))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        String wv = body(http.perform(post("/api/v1/workflows/{id}/versions",wid)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        String agent = """
                {"name":"cancel-agent-%s","instructions":"execute workflow","providerId":"mock","modelId":"hify-mock",
                "temperature":0.2,"maxTokens":2048,"maxTurns":6,"maxContextTurns":10,"enabledTools":[],"enabled":true}
                """.formatted(suffix);
        String aid = body(http.perform(post("/api/v1/agents").contentType("application/json").content(agent))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        http.perform(put("/api/v1/agents/{id}/workflow-binding",aid).contentType("application/json")
                .content(json.writeValueAsString(Map.of("workflowId",wid)))).andExpect(status().isOk());
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isOk());
        String conversation = body(http.perform(post("/api/v1/conversations").contentType("application/json")
                .content(json.writeValueAsString(Map.of("agentId",aid,"title","cancel fixture")))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();

        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try { release.await(); return List.of(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw new IllegalStateException("test retrieval interrupted"); }
        }).when(knowledge).search(kb,"blocked",3);
        try {
            String run = body(http.perform(post("/api/v1/conversations/{id}/runs",conversation).header("Idempotency-Key",suffix)
                    .contentType("application/json").content("{\"message\":\"blocked\"}")).andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString()).path("id").asText();
            assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            http.perform(post("/api/v1/runs/{id}/cancellations",run)).andExpect(status().isAccepted());
            assertThat(interrupted.await(2,TimeUnit.SECONDS)).as("real blocked worker interrupted before cleanup").isTrue();
            JsonNode finalRun = awaitTerminal(run);
            assertThat(finalRun.path("state").asText()).isEqualTo("CANCELLED");
            assertThat(finalRun.path("cancelRequestedAt").isNull()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,conversation)).isZero();
            assertThat(jdbc.queryForObject("select status from workflow_runs where workflow_version_id=?",String.class,wv)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("select count(*) from workflow_node_runs n join workflow_runs r on r.id=n.workflow_run_id where r.workflow_version_id=? and n.node_key='end'",Integer.class,wv)).isZero();
            String events = awaitCancellationEvent(run);
            assertThat(events).contains("workflow.cancelled","run.cancelled").doesNotContain("run.completed","workflow.completed");
        } finally { release.countDown(); }
    }

    private JsonNode awaitTerminal(String id) throws Exception {
        Instant deadline=Instant.now().plusSeconds(15);
        while(Instant.now().isBefore(deadline)) {
            JsonNode result=body(http.perform(get("/api/v1/runs/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if(!result.path("state").asText().equals("RUNNING")) return result;
            Thread.sleep(25);
        }
        throw new AssertionError("Run failed to converge");
    }
    private String awaitCancellationEvent(String id) throws Exception {
        Instant deadline=Instant.now().plusSeconds(5);
        while(Instant.now().isBefore(deadline)) {
            String value=http.perform(get("/api/v1/runs/{id}/events",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if(value.contains("run.cancelled")) return value;
            Thread.sleep(25);
        }
        throw new AssertionError("Cancellation event was not projected");
    }
    private JsonNode body(String value) throws Exception { return json.readTree(value); }
}
