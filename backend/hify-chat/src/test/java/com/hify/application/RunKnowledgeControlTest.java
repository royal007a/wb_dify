package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.*;
import com.hify.common.ExecutionLifecycle;
import com.hify.domain.*;
import com.hify.infra.*;
import com.hify.knowledge.api.*;
import com.hify.provider.api.*;
import com.hify.runtime.*;
import com.hify.runtime.state.*;
import com.hify.workflow.api.WorkflowCapabilityPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service admission only: actual model/HTTP cancellation is covered separately. */
class RunKnowledgeControlTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationDuringFirstCorpusPreventsSecondCorpusAndLoopAdmission(boolean lateFailure) throws Exception {
        try (Fixture f = new Fixture(Instant.now())) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.retrieval = corpus -> {
                if (corpus.equals("corpus-first")) {
                    entered.countDown();
                    try {
                        if (!release.await(60, TimeUnit.SECONDS)) throw new AssertionError("test release missing");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("fixture interrupted", interrupted);
                    }
                    if (lateFailure) throw new IllegalStateException("synthetic late source failure");
                }
                return List.of(f.citation(corpus));
            };
            try {
                f.service.convergeInterruptedRuns();
                assertThat(entered.await(60, TimeUnit.SECONDS)).as("first corpus admitted").isTrue();
                f.service.cancel("run"); // Public entry sets both durable and in-flight cancellation.
                assertThat(f.controls).hasSize(1);
                assertThat(f.controls.get(0)).isNotNull();
                assertThat(f.controls.get(0).isCancelled()).as("retrieval observes public cancellation").isTrue();
                release.countDown();
                f.finished.get(60, TimeUnit.SECONDS);
                assertThat(f.retrieved).containsExactly("corpus-first");
                verifyNoInteractions(f.clients, f.loop);
                assertThat(f.run.getState()).isEqualTo(RunState.CANCELLED);
                assertThat(f.run.getTerminalReason()).isEqualTo("CANCELLED");
                verify(f.events, never()).publish(eq("run"), eq("knowledge.retrieval.completed"), anyMap());
                verify(f.events, never()).publish(eq("run"), eq("knowledge.retrieval.failed"), anyMap());
                verify(f.messages, never()).save(any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void expiredDurableRunDoesNotRestartItsBudgetBeforeKnowledgeAdmission() throws Exception {
        try (Fixture f = new Fixture(Instant.now().minusSeconds(120))) {
            f.service.convergeInterruptedRuns();
            f.finished.get(60, TimeUnit.SECONDS);
            assertThat(f.retrieved).isEmpty();
            verifyNoInteractions(f.clients, f.loop);
            assertThat(f.run.getState()).isEqualTo(RunState.TIMED_OUT);
            assertThat(f.run.getTerminalReason()).isEqualTo("TIMEOUT");
        }
    }

    @Test
    void healthyRunAdmitsBothCorporaInPriorityOrderThenLoop() throws Exception {
        try (Fixture f = new Fixture(Instant.now())) {
            f.service.convergeInterruptedRuns();
            f.finished.get(60, TimeUnit.SECONDS);
            assertThat(f.retrieved).containsExactly("corpus-first", "corpus-second");
            verify(f.clients).create(f.provider);
            assertThat(mockingDetails(f.loop).getInvocations()).hasSize(1);
            assertThat(f.controls).hasSize(2).doesNotContainNull();
            Object loopControl=mockingDetails(f.loop).getInvocations().iterator().next().getArgument(8);
            assertThat(f.controls.get(0)).isSameAs(f.controls.get(1)).isSameAs(loopControl);
            assertThat(f.run.getState()).isEqualTo(RunState.COMPLETED);
            verify(f.events).publish(eq("run"), eq("knowledge.retrieval.completed"), argThat(payload ->
                    Integer.valueOf(2).equals(payload.get("citationCount"))));
        }
    }

    @Test
    void alreadyCancelledRecoveryDoesNotReadKnowledgeOrCreateModel() throws Exception {
        try (Fixture f = new Fixture(Instant.now())) {
            f.run.requestCancel();
            f.service.convergeInterruptedRuns(); // This path settles synchronously, without dispatch.
            assertThat(f.retrieved).isEmpty();
            verifyNoInteractions(f.clients, f.loop);
            assertThat(f.run.getState()).isEqualTo(RunState.CANCELLED);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AgentQueryService agents = mock(AgentQueryService.class);
        final ProviderQueryService providers = mock(ProviderQueryService.class);
        final ConversationRepository conversations = mock(ConversationRepository.class);
        final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        final AgentRunRepository runs = mock(AgentRunRepository.class);
        final ModelClientFactory clients = mock(ModelClientFactory.class);
        final ToolRuntime tools = mock(ToolRuntime.class);
        final RunEventBroker events = mock(RunEventBroker.class);
        final TransactionTemplate tx = mock(TransactionTemplate.class);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final List<String> retrieved = new CopyOnWriteArrayList<>();
        final List<com.hify.common.ExecutionControl> controls = new CopyOnWriteArrayList<>();
        final ProviderRuntimeConfig provider = new ProviderRuntimeConfig(
                "provider", "fixture", ProviderType.MOCK, null, null, "mock", true);
        Function<String, List<KnowledgeCitation>> retrieval = corpus -> List.of(citation(corpus));
        // Route by operation name so adding a controlled overload cannot accidentally turn
        // the red test green through an unstubbed mock returning an empty result.
        final KnowledgeRetrievalPort knowledge = mock(KnowledgeRetrievalPort.class, call -> {
            if (call.getMethod().getName().equals("searchRevision")) {
                String corpus = call.getArgument(0);
                retrieved.add(corpus);
                controls.add(call.getArguments().length==4?call.getArgument(3):null);
                return retrieval.apply(corpus);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        final QueryLoop loop = mock(QueryLoop.class, call -> {
            if (call.getMethod().getName().equals("run") || call.getMethod().getName().equals("resume")) {
                var context = ExecutionContextState.empty();
                return new QueryLoop.Result(TerminalReason.COMPLETED, "synthetic loop answer", List.of(), 1, 0,
                        null, List.of(), null, context,
                        ContinuationDecision.of(ContinuationAction.FINISH, "fixture", context, 1, null));
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        final AgentRun run;
        final RunApplicationService service;

        @SuppressWarnings("unchecked")
        Fixture(Instant createdAt) {
            run = new AgentRun("run", "conversation", "key", "hash", "question", createdAt);
            run.bindAgentSnapshot("version", "agent-digest");
            when(runs.findByStateIn(any())).thenReturn(List.of(run));
            when(runs.findById("run")).thenReturn(Optional.of(run));
            when(runs.findByIdForUpdate("run")).thenReturn(Optional.of(run));
            when(runs.requestCancel(eq("run"), eq(RunState.RUNNING), any())).thenAnswer(call -> {
                run.requestCancel(); return 1;
            });
            when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0))
                    .doInTransaction(new SimpleTransactionStatus()));
            when(runs.finishTerminal(anyString(), any(), anyLong(), any(), anyString(), nullable(String.class), anyInt(), anyInt(), any()))
                    .thenAnswer(call -> { run.finish(call.getArgument(3), call.getArgument(4), call.getArgument(5),
                            call.getArgument(6), call.getArgument(7)); return 1; });
            when(conversations.findById("conversation")).thenReturn(Optional.of(
                    new Conversation("conversation", "agent", "version", "fixture", Instant.now())));
            when(agents.requireVersion("version")).thenReturn(new AgentRuntimeSnapshot(
                    "version", "agent", 1, "agent-digest", "fixture", "policy", "provider", "mock",
                    0.2, 1024, 3, 10, List.of(), List.of(
                    new AgentKnowledgeBindingSnapshot("second", 2, 20, "corpus-second", "digest-2"),
                    new AgentKnowledgeBindingSnapshot("first", 2, 10, "corpus-first", "digest-1")),
                    null, List.of(), true));
            when(providers.requireEnabled("provider")).thenReturn(provider);
            when(tools.snapshot(anyString(), anySet(), anyList())).thenReturn(
                    new CapabilitySnapshot("capability", "tools-digest", Set.of(), List.of()));
            when(clients.create(provider)).thenReturn(mock(ModelClient.class));
            service = new RunApplicationService(agents, providers, conversations, messages, runs, clients, loop,
                    tools, mock(CommittedHistoryWriter.class), events, mock(RunCheckpointRepository.class),
                    mock(ChildAgentTaskService.class), knowledge, mock(WorkflowCapabilityPort.class),
                    new ObjectMapper(), tx, command -> worker.execute(() -> {
                        try { command.run(); finished.complete(null); }
                        catch (Throwable failure) { finished.completeExceptionally(failure); }
                    }), Duration.ofMinutes(1), 12, 2, 1, mock(ExecutionLifecycle.class));
        }

        KnowledgeCitation citation(String corpus) {
            return new KnowledgeCitation(corpus + "-chunk", corpus + "-doc", 1, 0, "synthetic content", "digest", 1, 1);
        }

        @Override public void close() throws Exception {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).as("fixture worker terminated").isTrue();
        }
    }
}
