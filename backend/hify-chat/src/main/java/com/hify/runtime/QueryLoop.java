package com.hify.runtime;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Component
public class QueryLoop {
    private final ToolRuntime toolRuntime;

    public QueryLoop(ToolRuntime toolRuntime) {
        this.toolRuntime = toolRuntime;
    }

    public Result run(List<RuntimeMessage> initialMessages, ModelClient modelClient,
                      String model, double temperature, Set<String> enabledTools,
                      int maxTurns, Duration timeout) {
        return run(initialMessages, modelClient, model, temperature, enabledTools,
                new RunPolicy(maxTurns, 12, 16_384, timeout, () -> false), RunObserver.NOOP);
    }

    public Result run(List<RuntimeMessage> initialMessages, ModelClient modelClient,
                      String model, double temperature, Set<String> enabledTools,
                      RunPolicy policy, RunObserver observer) {
        List<RuntimeMessage> messages = new ArrayList<>(initialMessages);
        long deadline = System.nanoTime() + policy.timeout().toNanos();
        int toolCalls = 0;
        int estimatedTokens = estimateTokens(messages);

        for (int turn = 1; turn <= policy.maxTurns(); turn++) {
            if (policy.cancelled().getAsBoolean()) {
                return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages, turn - 1, toolCalls);
            }
            if (System.nanoTime() >= deadline) {
                return terminal(TerminalReason.TIMEOUT, "Run timed out.", messages, turn - 1, toolCalls);
            }
            if (estimatedTokens >= policy.maxEstimatedTokens()) {
                return terminal(TerminalReason.TOKEN_BUDGET_EXCEEDED,
                        "Estimated token budget exceeded.", messages, turn - 1, toolCalls);
            }

            RuntimeMessage response;
            try {
                observer.onModelStarted(turn);
                response = modelClient.generate(new ModelRequest(
                        model, temperature, List.copyOf(messages), toolRuntime.definitions(enabledTools)));
            } catch (RuntimeException exception) {
                return terminal(TerminalReason.MODEL_ERROR,
                        "模型调用失败：" + exception.getMessage(), messages, turn, toolCalls);
            }
            messages.add(response);
            estimatedTokens += estimateTokens(List.of(response));
            observer.onModelCompleted(turn, response);

            if (policy.cancelled().getAsBoolean()) {
                return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages, turn, toolCalls);
            }
            if (System.nanoTime() >= deadline) {
                return terminal(TerminalReason.TIMEOUT, "Run timed out.", messages, turn, toolCalls);
            }

            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                return terminal(TerminalReason.COMPLETED, response.content(), messages, turn, toolCalls);
            }

            for (RuntimeMessage.ToolCall call : response.toolCalls()) {
                if (toolCalls >= policy.maxToolCalls()) {
                    return terminal(TerminalReason.TOOL_BUDGET_EXCEEDED,
                            "Tool-call budget exceeded.", messages, turn, toolCalls);
                }
                if (policy.cancelled().getAsBoolean()) {
                    return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages, turn, toolCalls);
                }
                toolCalls++;
                observer.onToolStarted(turn, call);
                ToolRuntime.ExecutionResult result = toolRuntime.execute(call, enabledTools);
                RuntimeMessage toolResult = RuntimeMessage.toolResult(call.id(), result.value(), result.error());
                messages.add(toolResult);
                estimatedTokens += estimateTokens(List.of(toolResult));
                observer.onToolCompleted(turn, call, result);
                if (result.fatal()) {
                    TerminalReason reason = result.permissionDenied()
                            ? TerminalReason.PERMISSION_DENIED : TerminalReason.FATAL_TOOL_ERROR;
                    return terminal(reason, String.valueOf(result.value()), messages, turn, toolCalls);
                }
            }
        }

        return terminal(TerminalReason.MAX_TURNS,
                "Agent 已达到最大执行轮数。", messages, policy.maxTurns(), toolCalls);
    }

    private Result terminal(TerminalReason reason, String text, List<RuntimeMessage> messages,
                            int turns, int toolCalls) {
        return new Result(reason, text, List.copyOf(messages), turns, toolCalls);
    }

    private int estimateTokens(List<RuntimeMessage> messages) {
        int characters = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return Math.max(1, (characters + 3) / 4);
    }

    public record RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                            Duration timeout, BooleanSupplier cancelled) {
        public RunPolicy {
            if (maxTurns < 1 || maxToolCalls < 0 || maxEstimatedTokens < 1) {
                throw new IllegalArgumentException("Run budgets must be positive");
            }
        }
    }

    public interface RunObserver {
        RunObserver NOOP = new RunObserver() {};
        default void onModelStarted(int turn) {}
        default void onModelCompleted(int turn, RuntimeMessage message) {}
        default void onToolStarted(int turn, RuntimeMessage.ToolCall call) {}
        default void onToolCompleted(int turn, RuntimeMessage.ToolCall call,
                                     ToolRuntime.ExecutionResult result) {}
    }

    public record Result(TerminalReason reason, String finalText,
                         List<RuntimeMessage> messages, int turns, int toolCalls) {}
}
