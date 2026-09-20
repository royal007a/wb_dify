package com.hify.api;

import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentToolBindingRequest;
import com.hify.agent.api.AgentUpdateRequest;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentVersionResponse;
import com.hify.application.RunApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.domain.AgentRun;
import com.hify.domain.Conversation;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.ConversationRepository;
import com.hify.infra.RunEventRepository;
import com.hify.memory.HistoryRecallService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "hify.run-timeout=5s")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hify_test")
            .withUsername("hify")
            .withPassword("hify");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired RunApplicationService service;
    @Autowired ConversationRepository conversations;
    @Autowired AgentRunRepository runs;
    @Autowired ChatMessageRepository messages;
    @Autowired RunEventRepository events;
    @Autowired AgentService agents;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;
    @Autowired HistoryRecallService historyRecall;

    @Test
    void chatSlicePinsPublishedVersionAndReplaysTerminalSseOnPostgres() throws Exception {
        String suffix = UUID.randomUUID().toString();
        AgentUpsertRequest draft = new AgentUpsertRequest(
                "Chat Agent " + suffix, "PostgreSQL chat slice", "Use tools when needed.",
                "mock", "hify-mock", 0.2, 2048, 6, 10, List.of("calculator"), true);
        String agentId = agents.create(draft);
        AgentVersionResponse versionOne = agents.publish(agentId);

        JsonNode firstConversation = createConversation(agentId, "Pinned v1");
        String conversationId = firstConversation.path("id").asText();
        assertThat(firstConversation.path("agentVersionId").asText()).isEqualTo(versionOne.id());

        String firstRunId = createRun(conversationId, "pg-chat-v1-" + suffix, "计算 17 * 23");
        AgentRun firstRun = awaitTerminal(firstRunId);
        assertThat(firstRun.getState()).isEqualTo(RunState.COMPLETED);
        assertThat(firstRun.getAgentVersionId()).isEqualTo(versionOne.id());
        assertThat(firstRun.getOutputMessage()).contains("391");
        assertThat(events.findByRunIdOrderByIdAsc(firstRunId))
                .extracting(event -> event.getEventType())
                .contains("message.delta", "tool.call.started", "tool.call.completed", "run.completed");
        HistoryRecallService.SearchResult recalled = historyRecall.search(firstRunId,
                new HistoryRecallService.SearchQuery("17 * 23", null, null, null, null, 5));
        assertThat(recalled.strategy()).isEqualTo("POSTGRES_FTS_KEYWORD");
        assertThat(recalled.matches()).isNotEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE schemaname = 'public' AND indexname = 'idx_detail_ref_fts'
                """, Long.class)).isEqualTo(1L);

        MvcResult stream = http.perform(get("/api/v1/runs/{id}/events/stream", firstRunId)
                        .header("Last-Event-ID", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        String replay = http.perform(asyncDispatch(stream)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).contains("event:run.completed", "event:message.delta");

        agents.update(agentId, new AgentUpdateRequest(
                draft.name(), draft.description(), "Version two instructions.",
                draft.providerId(), draft.modelId(), draft.temperature(), draft.maxTokens(),
                draft.maxTurns(), draft.maxContextTurns(), draft.enabled()));
        agents.replaceTools(agentId, new AgentToolBindingRequest(List.of("calculator", "current_time")));
        AgentVersionResponse versionTwo = agents.publish(agentId);
        assertThat(versionTwo.id()).isNotEqualTo(versionOne.id());

        String oldConversationRunId = createRun(
                conversationId, "pg-chat-old-version-" + suffix, "计算 6 * 7");
        AgentRun oldConversationRun = awaitTerminal(oldConversationRunId);
        assertThat(oldConversationRun.getState()).isEqualTo(RunState.COMPLETED);
        assertThat(oldConversationRun.getAgentVersionId()).isEqualTo(versionOne.id());
        assertThat(oldConversationRun.getOutputMessage()).contains("42");

        JsonNode secondConversation = createConversation(agentId, "Pinned v2");
        assertThat(secondConversation.path("agentVersionId").asText()).isEqualTo(versionTwo.id());
    }

    @Test
    void concurrentAgentCreatesUsePostgresUniqueConstraintAsFinalGuard() throws Exception {
        String name = "Concurrent Agent " + UUID.randomUUID();
        AgentUpsertRequest request = new AgentUpsertRequest(
                name, "PostgreSQL uniqueness", "help", "mock", "hify-mock",
                0.2, 2048, 6, 10, List.of("calculator"), true);
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateAttempt>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        agents.create(request);
                        return new CreateAttempt(true, null);
                    } catch (BizException exception) {
                        return new CreateAttempt(false, exception.errorCode());
                    }
                }));
            }
            ready.await();
            start.countDown();

            List<CreateAttempt> results = new ArrayList<>();
            for (Future<CreateAttempt> future : futures) results.add(future.get());
            assertThat(results).filteredOn(CreateAttempt::created).hasSize(1);
            assertThat(results).filteredOn(result -> !result.created())
                    .extracting(CreateAttempt::errorCode).containsOnly(ErrorCode.CONFLICT);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_definitions WHERE name = ?", Long.class, name))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdempotentCreatesConvergeAndTerminalCasHasOneWinner() throws Exception {
        String conversationId = "conv-" + UUID.randomUUID();
        conversations.saveAndFlush(new Conversation(
                conversationId, "demo-agent", "PostgreSQL concurrency", Instant.now()));

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RunApplicationService.CreateResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.create(conversationId, "same-key", "计算 17 * 23");
                }));
            }
            ready.await();
            start.countDown();

            List<RunApplicationService.CreateResult> results = new ArrayList<>();
            for (Future<RunApplicationService.CreateResult> future : futures) {
                results.add(future.get());
            }

            assertThat(results).extracting(result -> result.run().getId()).containsOnly(results.get(0).run().getId());
            assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
            AgentRun completed = awaitTerminal(results.get(0).run().getId());
            assertThat(completed.getState()).isEqualTo(RunState.COMPLETED);
            assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversationId)).hasSize(2);
            assertThat(events.findByRunIdOrderByIdAsc(completed.getId()).stream()
                    .filter(event -> event.getEventType().startsWith("run."))
                    .filter(event -> event.getEventType().equals("run.completed")
                            || event.getEventType().equals("run.failed")
                            || event.getEventType().equals("run.cancelled")))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        String casRunId = "run-" + UUID.randomUUID();
        AgentRun pending = runs.saveAndFlush(new AgentRun(casRunId, conversationId,
                "cas-key", "hash", "input", Instant.now()));
        long expectedVersion = pending.getVersion();

        ExecutorService casPool = Executors.newFixedThreadPool(callers);
        CountDownLatch casReady = new CountDownLatch(callers);
        CountDownLatch casStart = new CountDownLatch(1);
        List<Future<Integer>> casFutures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                int attempt = i;
                casFutures.add(casPool.submit(() -> {
                    casReady.countDown();
                    casStart.await();
                    return runs.finishTerminal(casRunId, RunState.RUNNING, expectedVersion,
                            attempt % 2 == 0 ? RunState.COMPLETED : RunState.FAILED,
                            "ATTEMPT_" + attempt, "output", 1, 0, Instant.now());
                }));
            }
            casReady.await();
            casStart.countDown();
            int winners = 0;
            for (Future<Integer> future : casFutures) winners += future.get();
            assertThat(winners).isEqualTo(1);
            assertThat(runs.findById(casRunId).orElseThrow().getState().terminal()).isTrue();
        } finally {
            casPool.shutdownNow();
        }
    }

    private AgentRun awaitTerminal(String runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            AgentRun run = runs.findById(runId).orElseThrow();
            if (run.getState().terminal()) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Run did not reach a terminal state");
    }

    private JsonNode createConversation(String agentId, String title) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("agentId", agentId, "title", title));
        String response = http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String createRun(String conversationId, String idempotencyKey, String message) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("message", message));
        String response = http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("id").asText();
    }

    private record CreateAttempt(boolean created, ErrorCode errorCode) {}
}
