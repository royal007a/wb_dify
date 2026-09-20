package com.hify.runtime;

import com.hify.runtime.plan.ReplanAction;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.ReplanDecision;
import com.hify.runtime.state.ContinuationAction;
import com.hify.runtime.state.ContinuationDecision;
import com.hify.runtime.state.GapState;
import com.hify.runtime.state.RecoveryNarrative;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLoopTest {
    private final QueryLoop loop = new QueryLoop(new ToolRuntime());

    @Test
    void completesMockToolRoundTripWithPairedResult() {
        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("计算 17 * 23")),
                new MockModelClient(), "hify-mock", 0.2,
                Set.of("calculator"),
                new QueryLoop.RunPolicy(6, 12, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(result.finalText()).contains("391");
        assertThat(result.toolCalls()).isEqualTo(1);
        RuntimeMessage toolCall = result.messages().get(1);
        RuntimeMessage toolResult = result.messages().get(2);
        assertThat(toolResult.toolCallId()).isEqualTo(toolCall.toolCalls().get(0).id());
        assertThat(result.finalDecision().action()).isEqualTo(ContinuationAction.FINISH);
        assertThat(result.contextState().evidence()).hasSize(1);
        assertThat(result.contextState().hasVerifiedCoverageForRequiredClaims()).isTrue();
    }

    @Test
    void commitsCanonicalHistoryBeforeTheNextModelRequest() {
        ToolRuntime tools = new ToolRuntime();
        CapabilitySnapshot capability = tools.snapshot("agent-v1", Set.of("calculator"));
        List<String> operations = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = request -> {
            if (modelCalls.incrementAndGet() == 1) {
                return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                        "history-call", "calculator", Map.of("expression", "2+2"))));
            }
            assertThat(operations).containsExactly("model:1", "tool:history-call");
            return RuntimeMessage.assistant("4");
        };
        RunRuntimeIdentity identity = new RunRuntimeIdentity("run-1", capability.revision(),
                capability.toolSchemaDigest(), (attempt, revision) -> true,
                (operation, messages) -> {
                    operations.add(operation);
                    return new HistoryCommitter.CommitReceipt(operations.size(), operation, false);
                });

        QueryLoop.Result result = new QueryLoop(tools).run(List.of(RuntimeMessage.user("2+2")),
                model, "mock", 0, capability,
                new QueryLoop.RunPolicy(3, 3, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP, identity);

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(operations).containsExactly("model:1", "tool:history-call", "model:2");
    }

    @Test
    void rejectsDuplicateToolCallIdentity() {
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("duplicate", "calculator", Map.of("expression", "1+1"))));
        QueryLoop.Result result = loop.run(List.of(RuntimeMessage.user("repeat")), model,
                "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(3, 3, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.DUPLICATE_TOOL_CALL);
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void stopsBeforeExecutingToolPastBudget() {
        ModelClient alwaysCallsTool = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("call-1", "current_time", Map.of())));

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("time")), alwaysCallsTool, "mock", 0,
                Set.of("current_time"),
                new QueryLoop.RunPolicy(3, 0, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.TOOL_BUDGET_EXCEEDED);
        assertThat(result.toolCalls()).isZero();
    }

    @Test
    void searchIsNavigationOnlyAndCannotFinishWithoutCanonicalDetail() {
        ToolRuntime tools = recallTools();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = request -> calls.incrementAndGet() == 1
                ? RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                "search-1", "history.search", Map.of("query", "old fact"))))
                : RuntimeMessage.assistant("I will guess from the summary");

        QueryLoop.Result result = new QueryLoop(tools).run(List.of(RuntimeMessage.user("question")),
                model, "mock", 0, Set.of("history.search"),
                new QueryLoop.RunPolicy(3, 3, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(result.contextState().evidence()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(
                        com.hify.runtime.state.EvidenceItem.Status.UNVERIFIED));
        assertThat(result.contextState().openBlockingGaps()).isNotEmpty();
    }

    @Test
    void enforcesDedicatedRecallBudgetBeforeRepeatedInvestigation() {
        ToolRuntime tools = recallTools();
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                "search-" + calls.incrementAndGet(), "history.search", Map.of("query", "same"))));
        CapabilitySnapshot capability = tools.snapshot("recall", Set.of("history.search"));

        QueryLoop.Result result = new QueryLoop(tools).run(List.of(RuntimeMessage.user("question")),
                model, "mock", 0, capability,
                new QueryLoop.RunPolicy(4, 4, 4096, 2, 1,
                        1, 512, 5_000, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP, RunRuntimeIdentity.local(capability));

        assertThat(result.reason()).isEqualTo(TerminalReason.RECALL_BUDGET_EXCEEDED);
        assertThat(result.toolCalls()).isEqualTo(1);
    }

    @Test
    void stopsWhenCancelledBeforeModelCall() {
        AtomicBoolean cancelled = new AtomicBoolean(true);

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("hello")), request -> RuntimeMessage.assistant("not called"),
                "mock", 0, Set.of(),
                new QueryLoop.RunPolicy(3, 1, 4096, Duration.ofSeconds(5), cancelled::get),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.CANCELLED);
        assertThat(result.turns()).isZero();
    }

    @Test
    void rejectsModelRequestedToolOutsideAgentPolicy() {
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("call-1", "calculator", Map.of("expression", "1+1"))));

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("calculate")), model, "mock", 0, Set.of(),
                new QueryLoop.RunPolicy(3, 2, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.PERMISSION_DENIED);
    }

    @Test
    void enforcesEstimatedTokenBudgetBeforeModelCall() {
        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("a".repeat(100))), request -> RuntimeMessage.assistant("not called"),
                "mock", 0, Set.of(),
                new QueryLoop.RunPolicy(3, 1, 2, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.TOKEN_BUDGET_EXCEEDED);
    }

    @Test
    void archivesLargeToolResultBeforeTheNextModelRequestWithoutChangingCanonicalHistory() {
        ToolRuntime largeResultTool = new ToolRuntime() {
            @Override
            public ExecutionResult execute(RuntimeMessage.ToolCall call, CapabilitySnapshot capability,
                                           ToolExecutionLease lease,
                                           com.hify.common.ExecutionControl control) {
                return ExecutionResult.success("diagnostic:" + "x".repeat(4_000));
            }
        };
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicBoolean sawArchiveReference = new AtomicBoolean();
        ModelClient model = request -> {
            if (modelCalls.incrementAndGet() == 1) {
                return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                        "large-output", "calculator", Map.of("expression", "1+1"))));
            }
            sawArchiveReference.set(request.messages().stream()
                    .anyMatch(message -> "tool".equals(message.role())
                            && message.content().contains("history://tool-result/large-output")));
            return RuntimeMessage.assistant("done");
        };

        QueryLoop.Result result = new QueryLoop(largeResultTool).run(
                List.of(RuntimeMessage.user("inspect")), model, "mock", 0,
                Set.of("calculator"),
                new QueryLoop.RunPolicy(3, 3, 512, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(sawArchiveReference).isTrue();
        assertThat(result.messages()).filteredOn(message -> "tool".equals(message.role()))
                .singleElement().extracting(RuntimeMessage::content).asString()
                .contains("x".repeat(100));
    }

    @Test
    void locallyReplansInvalidCalculatorArgumentsAndTracksDistinctCauseCoordinates() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> observedPlanVersions = new ArrayList<>();
        List<ReplanAction> observedActions = new ArrayList<>();
        ModelClient repairingModel = request -> switch (calls.incrementAndGet()) {
            case 1 -> RuntimeMessage.toolCalls(List.of(
                    new RuntimeMessage.ToolCall("bad-call", "calculator", Map.of())));
            case 2 -> {
                assertThat(request.messages().get(request.messages().size() - 1).content())
                        .contains("Missing required argument");
                yield RuntimeMessage.toolCalls(List.of(
                        new RuntimeMessage.ToolCall("fixed-call", "calculator", Map.of("expression", "6*7"))));
            }
            default -> RuntimeMessage.assistant("42");
        };

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("calculate")), repairingModel, "mock", 0,
                Set.of("calculator"),
                new QueryLoop.RunPolicy(5, 5, 4096, 2, Duration.ofSeconds(5), () -> false),
                new QueryLoop.RunObserver() {
                    @Override
                    public void onPlanCreated(ExecutionPlan plan) {
                        observedPlanVersions.add(plan.version());
                    }

                    @Override
                    public void onReplanDecided(ExecutionPlan plan, ReplanDecision decision) {
                        observedActions.add(decision.action());
                    }
                });

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(result.finalText()).isEqualTo("42");
        assertThat(result.plan().version()).isEqualTo(2);
        assertThat(result.replanDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.action()).isEqualTo(ReplanAction.LOCAL_REPLAN);
            assertThat(decision.failurePointId()).isNotEqualTo(decision.rootCausePointId());
            assertThat(decision.rollbackPointId()).isNotBlank();
            assertThat(decision.replanFromStepId()).isEqualTo(decision.rootCausePointId());
        });
        assertThat(result.checkpoint().planVersion()).isEqualTo(2);
        assertThat(observedPlanVersions).containsExactly(1, 2);
        assertThat(observedActions).containsExactly(ReplanAction.LOCAL_REPLAN);
    }

    @Test
    void suggestsKnownReadOnlyAlternativeForUnavailableTool() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient repairingModel = request -> switch (calls.incrementAndGet()) {
            case 1 -> RuntimeMessage.toolCalls(List.of(
                    new RuntimeMessage.ToolCall("wrong-name", "math", Map.of("expression", "2+3"))));
            case 2 -> RuntimeMessage.toolCalls(List.of(
                    new RuntimeMessage.ToolCall("right-name", "calculator", Map.of("expression", "2+3"))));
            default -> RuntimeMessage.assistant("5");
        };

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("2+3")), repairingModel, "mock", 0,
                Set.of("calculator"),
                new QueryLoop.RunPolicy(5, 5, 4096, 2, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(result.replanDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.action()).isEqualTo(ReplanAction.LOCAL_REPLAN);
            assertThat(decision.evidence()).contains("readOnlyAlternative=calculator");
        });
    }

    @Test
    void asksHumanWhenUnavailableToolHasNoSafeAlternative() {
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("unknown-call", "weather", Map.of()),
                new RuntimeMessage.ToolCall("skipped-call", "calculator", Map.of("expression", "1+1"))));

        QueryLoop.Result result = loop.run(
                List.of(RuntimeMessage.user("weather")), model, "mock", 0, Set.of(),
                new QueryLoop.RunPolicy(3, 3, 4096, 2, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(result.replanDecisions()).singleElement()
                .extracting(decision -> decision.action()).isEqualTo(ReplanAction.ASK_HUMAN);
        assertThat(result.messages()).filteredOn(message -> "tool".equals(message.role()))
                .extracting(RuntimeMessage::toolCallId)
                .containsExactly("unknown-call", "skipped-call");
    }

    private ToolRuntime recallTools() {
        return new ToolRuntime(List.of(new RuntimeToolExtension() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(new ToolDefinition("history.search", "search history",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string")), "required", List.of("query")), "read"));
            }

            @Override
            public ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call,
                                                       ToolExecutionLease lease,
                                                       com.hify.common.ExecutionControl control) {
                return ToolRuntime.ExecutionResult.success(new NavigationResult(
                        "history-search:fixed", "a".repeat(64), 12));
            }
        }));
    }

    private record NavigationResult(String sourceRef, String valueDigest, int estimatedTokens)
            implements ToolEvidencePayload {
        @Override public EvidenceKind evidenceKind() { return EvidenceKind.NAVIGATION; }
        @Override public String evidenceSummary() { return "navigation only"; }
    }

    @Test
    void resumesAfterCheckpointWithoutReplayingCompletedToolAttempt() {
        QueryLoop.Result first = loop.run(
                List.of(RuntimeMessage.user("计算 17 * 23")), new MockModelClient(), "mock", 0,
                Set.of("calculator"),
                new QueryLoop.RunPolicy(1, 3, 4096, 2, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);
        assertThat(first.reason()).isEqualTo(TerminalReason.MAX_TURNS);
        assertThat(first.checkpoint().toolCalls()).isEqualTo(1);

        QueryLoop.Result resumed = loop.resume(first.checkpoint(),
                request -> RuntimeMessage.assistant("391"), "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(3, 3, 4096, 2, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(resumed.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(resumed.toolCalls()).isEqualTo(1);
        assertThat(resumed.turns()).isEqualTo(2);
        assertThat(resumed.plan().id()).isEqualTo(first.plan().id());
        assertThat(resumed.plan().digest()).isEqualTo(first.plan().digest());
        assertThat(resumed.checkpoint().evidenceVersion()).isEqualTo(first.checkpoint().evidenceVersion());
    }

    @Test
    void retriesTransientCalculatorFailureWithoutReplanning() {
        AtomicInteger executions = new AtomicInteger();
        ToolRuntime flakyCalculator = new ToolRuntime() {
            @Override
            public ExecutionResult execute(RuntimeMessage.ToolCall call, CapabilitySnapshot capability,
                                           ToolExecutionLease lease,
                                           com.hify.common.ExecutionControl control) {
                if (executions.incrementAndGet() == 1) return ExecutionResult.transientFailure("temporary timeout");
                return super.execute(call, capability, lease, control);
            }
        };
        QueryLoop retryingLoop = new QueryLoop(flakyCalculator);
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = request -> modelCalls.incrementAndGet() == 1
                ? RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                "calculator-call", "calculator", Map.of("expression", "6*7"))))
                : RuntimeMessage.assistant("42");
        List<ContinuationAction> actions = new ArrayList<>();
        List<RecoveryNarrative> narratives = new ArrayList<>();

        QueryLoop.Result result = retryingLoop.run(List.of(RuntimeMessage.user("calculate 6*7")),
                model, "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(3, 4, 4096, 2, 1,
                        Duration.ofSeconds(5), () -> false),
                new QueryLoop.RunObserver() {
                    @Override public void onContinuationDecided(ContinuationDecision decision) {
                        actions.add(decision.action());
                    }
                    @Override public void onRecoveryNarrated(RecoveryNarrative narrative) {
                        narratives.add(narrative);
                    }
                });

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.replanDecisions()).isEmpty();
        assertThat(actions).containsExactly(ContinuationAction.RETRY,
                ContinuationAction.CONTINUE, ContinuationAction.FINISH);
        assertThat(narratives).singleElement().satisfies(narrative -> {
            assertThat(narrative.decision()).isEqualTo(ContinuationAction.RETRY);
            assertThat(narrative.failurePointId()).isNotEqualTo(narrative.rootCausePointId());
        });
    }

    @Test
    void interruptsWhenTransientRetryBudgetIsExhausted() {
        ToolRuntime unavailableCalculator = new ToolRuntime() {
            @Override
            public ExecutionResult execute(RuntimeMessage.ToolCall call, CapabilitySnapshot capability,
                                           ToolExecutionLease lease,
                                           com.hify.common.ExecutionControl control) {
                return ExecutionResult.transientFailure("temporary timeout");
            }
        };
        QueryLoop retryingLoop = new QueryLoop(unavailableCalculator);
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("calculator-call", "calculator", Map.of("expression", "6*7"))));

        QueryLoop.Result result = retryingLoop.run(List.of(RuntimeMessage.user("calculate 6*7")),
                model, "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(3, 4, 4096, 2, 1,
                        Duration.ofSeconds(5), () -> false), QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.RETRY_EXHAUSTED);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(result.finalDecision().action()).isEqualTo(ContinuationAction.INTERRUPT);
        assertThat(result.contextState().openBlockingGaps()).singleElement()
                .extracting(GapState::kind).isEqualTo(GapState.Kind.TRANSIENT_FAILURE);
    }

    @Test
    void clarifiesAfterRepeatedInvalidActionMakesNoProgress() {
        ModelClient repeatingModel = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("bad-" + request.messages().size(), "calculator", Map.of())));
        List<ContinuationAction> actions = new ArrayList<>();

        QueryLoop.Result result = loop.run(List.of(RuntimeMessage.user("calculate")),
                repeatingModel, "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(5, 6, 4096, 4, 1,
                        Duration.ofSeconds(5), () -> false),
                new QueryLoop.RunObserver() {
                    @Override public void onContinuationDecided(ContinuationDecision decision) {
                        actions.add(decision.action());
                    }
                });

        assertThat(result.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(result.plan().version()).isEqualTo(2);
        assertThat(result.finalDecision().action()).isEqualTo(ContinuationAction.CLARIFY);
        assertThat(actions).containsExactly(ContinuationAction.REPLAN, ContinuationAction.CLARIFY);
        assertThat(result.contextState().openBlockingGaps()).extracting(GapState::kind)
                .contains(GapState.Kind.INVALID_INPUT, GapState.Kind.NO_PROGRESS);
    }

    @Test
    void clarifiesMissingCalculatorInputWithoutUsingReplanWhenBudgetIsZero() {
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("bad-call", "calculator", Map.of())));

        QueryLoop.Result result = loop.run(List.of(RuntimeMessage.user("calculate")),
                model, "mock", 0, Set.of("calculator"),
                new QueryLoop.RunPolicy(2, 2, 4096, 0, 0,
                        Duration.ofSeconds(5), () -> false), QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(result.finalDecision().action()).isEqualTo(ContinuationAction.CLARIFY);
        assertThat(result.contextState().openBlockingGaps()).singleElement()
                .extracting(GapState::kind).isEqualTo(GapState.Kind.INVALID_INPUT);
    }

    @Test
    void permissionDenialUsesInterruptExitAndKeepsEvidence() {
        ModelClient model = request -> RuntimeMessage.toolCalls(List.of(
                new RuntimeMessage.ToolCall("denied", "calculator", Map.of("expression", "1+1"))));

        QueryLoop.Result result = loop.run(List.of(RuntimeMessage.user("calculate")),
                model, "mock", 0, Set.of(),
                new QueryLoop.RunPolicy(2, 2, 4096, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP);

        assertThat(result.reason()).isEqualTo(TerminalReason.PERMISSION_DENIED);
        assertThat(result.finalDecision().action()).isEqualTo(ContinuationAction.INTERRUPT);
        assertThat(result.contextState().evidence()).isNotEmpty();
        assertThat(result.contextState().openBlockingGaps()).singleElement()
                .extracting(GapState::kind).isEqualTo(GapState.Kind.MISSING_PERMISSION);
    }
}
