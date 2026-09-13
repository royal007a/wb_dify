package com.hify.runtime;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
