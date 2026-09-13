package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.RunApplicationService;
import com.hify.domain.AgentRun;
import com.hify.domain.RunCheckpoint;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.RunCheckpointRepository;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.state.ExecutionContextState;
import com.hify.runtime.state.GapState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    @Autowired RunCheckpointRepository checkpoints;
    @Autowired AgentRunRepository runs;
    @Autowired RunApplicationService runService;

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
        assertThat(events.toString()).contains("plan.created", "step.try.started", "step.try.completed",
                "checkpoint.created", "tool.call.started", "tool.call.completed",
                "context.state.updated", "continuation.decided", "run.completed");
        assertThat(checkpoints.findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(runId))
                .hasValueSatisfying(checkpoint -> {
                    assertThat(checkpoint.getTurnNo()).isEqualTo(1);
                    assertThat(checkpoint.getToolCalls()).isEqualTo(1);
                    assertThat(checkpoint.getPlanVersion()).isEqualTo(1);
                    assertThat(checkpoint.getMessagesJson()).contains("toolCallId");
                    assertThat(checkpoint.getEvidenceVersion()).isPositive();
                    assertThat(checkpoint.getContextJson()).contains("TOOL_RESULT");
                });

        AgentRun cancellable = new AgentRun("cancel-persistence-run", conversationId,
                "cancel-persistence-key", "hash", "wait", Instant.now());
        runs.saveAndFlush(cancellable);
        runService.cancel(cancellable.getId());
        assertThat(runs.findById(cancellable.getId())).hasValueSatisfying(saved ->
                assertThat(saved.getCancelRequestedAt()).isNotNull());

        AgentRun awaitingInput = new AgentRun("needs-input-run", conversationId,
                "needs-input-key", "hash", "calculate", Instant.now());
        awaitingInput.finish(RunState.NEEDS_INPUT, "HUMAN_INPUT_REQUIRED",
                "expression is required", 1, 1);
        runs.saveAndFlush(awaitingInput);
        ExecutionPlan awaitingPlan = ExecutionPlan.initial("calculate");
        GapState inputGap = GapState.open(GapState.Kind.MISSING_INPUT,
                "expression is required", true, "user:expression",
                List.of("ask_user"), "attempt-needs-input");
        ExecutionContextState awaitingContext = ExecutionContextState.empty().openGap(inputGap);
        List<RuntimeMessage> awaitingMessages = List.of(
                RuntimeMessage.system("test"), RuntimeMessage.user("calculate"));
        checkpoints.saveAndFlush(new RunCheckpoint(awaitingInput.getId(), 1, "needs-input-checkpoint",
                1, 1, awaitingPlan.id(), awaitingPlan.version(), awaitingPlan.digest(),
                objectMapper.writeValueAsString(awaitingPlan), awaitingContext.evidenceVersion(),
                awaitingContext.gapVersion(), objectMapper.writeValueAsString(awaitingContext),
                objectMapper.writeValueAsString(awaitingMessages), true, Instant.now()));

        String resumedBody = "{\"message\":\"6*7\",\"resume\":{\"runId\":\"needs-input-run\","
                + "\"gapIds\":[\"" + inputGap.id() + "\"]}}";
        JsonNode resumedCreate = json(http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "resume-input-key")
                        .contentType(MediaType.APPLICATION_JSON).content(resumedBody))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String resumedRunId = resumedCreate.path("id").asText();
        assertThat(resumedCreate.path("resumedFromRunId").asText()).isEqualTo(awaitingInput.getId());
        assertThat(resumedCreate.path("resolvedGapIds").get(0).asText()).isEqualTo(inputGap.id());
        awaitState(resumedRunId, RunState.COMPLETED);
        RunCheckpoint resumedCheckpoint = checkpoints
                .findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(resumedRunId).orElseThrow();
        ExecutionContextState restored = objectMapper.readValue(
                resumedCheckpoint.getContextJson(), ExecutionContextState.class);
        assertThat(restored.gaps()).singleElement()
                .extracting(GapState::status).isEqualTo(GapState.Status.RESOLVED);
        assertThat(restored.evidence()).anyMatch(item ->
                item.type() == com.hify.runtime.state.EvidenceItem.Type.USER_INPUT);
        JsonNode resumedEvents = json(http.perform(get("/api/v1/runs/{id}/events", resumedRunId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(resumedEvents.toString()).contains("run.input.accepted", "checkpoint.restored", "run.completed");

        AgentRun interrupted = new AgentRun("checkpoint-recovery-run", conversationId,
                "checkpoint-recovery-key", "hash", "recover", Instant.now());
        runs.saveAndFlush(interrupted);
        List<RuntimeMessage> checkpointMessages = List.of(
                RuntimeMessage.system("test"),
                RuntimeMessage.user("计算 8 * 8"),
                RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                        "recovered-tool-call", "calculator", Map.of("expression", "8*8")))),
                RuntimeMessage.toolResult("recovered-tool-call", "64", false));
        ExecutionPlan recoveryPlan = ExecutionPlan.initial("计算 8 * 8");
        ExecutionContextState recoveryContext = ExecutionContextState.empty();
        checkpoints.saveAndFlush(new RunCheckpoint(interrupted.getId(), 1, "checkpoint-recovery-1",
                1, 1, recoveryPlan.id(), recoveryPlan.version(), recoveryPlan.digest(),
                objectMapper.writeValueAsString(recoveryPlan), recoveryContext.evidenceVersion(),
                recoveryContext.gapVersion(), objectMapper.writeValueAsString(recoveryContext),
                objectMapper.writeValueAsString(checkpointMessages),
                true, Instant.now()));

        runService.convergeInterruptedRuns();
        awaitState(interrupted.getId(), RunState.COMPLETED);
        JsonNode recoveryEvents = json(http.perform(get("/api/v1/runs/{id}/events", interrupted.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(recoveryEvents.toString()).contains("checkpoint.restored", "run.completed");
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

    private void awaitState(String runId, RunState expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            RunState state = runs.findById(runId).orElseThrow().getState();
            if (state == expected) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not reach state " + expected);
    }
}
