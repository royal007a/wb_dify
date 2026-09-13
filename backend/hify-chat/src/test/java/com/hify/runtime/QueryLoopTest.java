package com.hify.runtime;

import com.hify.runtime.plan.ReplanAction;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.ReplanDecision;
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
    }
}
