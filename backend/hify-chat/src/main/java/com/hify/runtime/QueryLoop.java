package com.hify.runtime;

import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import com.hify.runtime.plan.DeterministicReplanPolicy;
import com.hify.runtime.plan.ExecutionCheckpoint;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.PlanPhase;
import com.hify.runtime.plan.PlanStateMachine;
import com.hify.runtime.plan.PlanStep;
import com.hify.runtime.plan.ReplanAction;
import com.hify.runtime.plan.ReplanDecision;
import com.hify.runtime.plan.StepAttempt;
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
        ExecutionPlan plan = ExecutionPlan.initial(goal(initialMessages));
        return run(initialMessages, modelClient, model, temperature, enabledTools,
                policy, observer, plan, 0, 0, false);
    }

    public Result resume(ExecutionCheckpoint checkpoint, ModelClient modelClient,
                         String model, double temperature, Set<String> enabledTools,
                         RunPolicy policy, RunObserver observer) {
        ExecutionPlan plan = checkpoint.plan();
        observer.onCheckpointRestored(checkpoint);
        return run(checkpoint.messages(), modelClient, model, temperature, enabledTools,
                policy, observer, plan, checkpoint.turn(), checkpoint.toolCalls(), true);
    }

    private Result run(List<RuntimeMessage> initialMessages, ModelClient modelClient,
                       String model, double temperature, Set<String> enabledTools,
                       RunPolicy policy, RunObserver observer, ExecutionPlan initialPlan,
                       int completedTurns, int completedToolCalls, boolean resumed) {
        List<RuntimeMessage> messages = new ArrayList<>(initialMessages);
        ExecutionControl control = ExecutionControl.withTimeout(policy.timeout(), policy.cancelled());
        int toolCalls = completedToolCalls;
        int estimatedTokens = estimateTokens(messages);
        ExecutionPlan plan = initialPlan;
        PlanStateMachine state = new PlanStateMachine();
        List<ReplanDecision> replanDecisions = new ArrayList<>();
        ExecutionCheckpoint checkpoint = ExecutionCheckpoint.capture(completedTurns, toolCalls, plan, messages);
        if (!resumed) {
            observer.onPlanCreated(plan);
            observer.onCheckpointCreated(checkpoint);
        }

        for (int turn = completedTurns + 1; turn <= policy.maxTurns(); turn++) {
            if (control.isCancelled()) {
                transitionIfPossible(state, PlanPhase.CANCELLED);
                return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages,
                        turn - 1, toolCalls, plan, replanDecisions, checkpoint);
            }
            if (control.isExpired()) {
                transitionIfPossible(state, PlanPhase.FAILED);
                return terminal(TerminalReason.TIMEOUT, "Run timed out.", messages,
                        turn - 1, toolCalls, plan, replanDecisions, checkpoint);
            }
            if (estimatedTokens >= policy.maxEstimatedTokens()) {
                transitionIfPossible(state, PlanPhase.FAILED);
                return terminal(TerminalReason.TOKEN_BUDGET_EXCEEDED,
                        "Estimated token budget exceeded.", messages, turn - 1, toolCalls,
                        plan, replanDecisions, checkpoint);
            }

            RuntimeMessage response;
            try {
                observer.onModelStarted(turn);
                response = modelClient.generate(new ModelRequest(
                        model, temperature, List.copyOf(messages),
                        toolRuntime.definitions(enabledTools), control));
            } catch (ExecutionCancelledException exception) {
                transitionIfPossible(state, PlanPhase.CANCELLED);
                return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages,
                        turn - 1, toolCalls, plan, replanDecisions, checkpoint);
            } catch (RuntimeException exception) {
                if (control.isCancelled()) {
                    transitionIfPossible(state, PlanPhase.CANCELLED);
                    return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages,
                            turn - 1, toolCalls, plan, replanDecisions, checkpoint);
                }
                if (control.isExpired()) {
                    transitionIfPossible(state, PlanPhase.FAILED);
                    return terminal(TerminalReason.TIMEOUT, "Run timed out.", messages,
                            turn - 1, toolCalls, plan, replanDecisions, checkpoint);
                }
                transitionIfPossible(state, PlanPhase.FAILED);
                return terminal(TerminalReason.MODEL_ERROR,
                        "模型调用失败：" + exception.getMessage(), messages, turn, toolCalls,
                        plan, replanDecisions, checkpoint);
            }
            messages.add(response);
            estimatedTokens += estimateTokens(List.of(response));
            observer.onModelCompleted(turn, response);

            if (control.isCancelled()) {
                transitionIfPossible(state, PlanPhase.CANCELLED);
                return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint);
            }
            if (control.isExpired()) {
                transitionIfPossible(state, PlanPhase.FAILED);
                return terminal(TerminalReason.TIMEOUT, "Run timed out.", messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint);
            }

            if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
                transitionIfPossible(state, PlanPhase.COMPLETED);
                return terminal(TerminalReason.COMPLETED, response.content(), messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint);
            }

            ReplanDecision pendingDecision = null;
            for (RuntimeMessage.ToolCall call : response.toolCalls()) {
                if (toolCalls >= policy.maxToolCalls()) {
                    transitionIfPossible(state, PlanPhase.FAILED);
                    return terminal(TerminalReason.TOOL_BUDGET_EXCEEDED,
                            "Tool-call budget exceeded.", messages, turn, toolCalls,
                            plan, replanDecisions, checkpoint);
                }
                if (control.isCancelled()) {
                    transitionIfPossible(state, PlanPhase.CANCELLED);
                    return terminal(TerminalReason.CANCELLED, "Run cancelled.", messages,
                            turn, toolCalls, plan, replanDecisions, checkpoint);
                }
                toolCalls++;
                PlanStep step = plan.steps().get(0);
                if (pendingDecision != null && pendingDecision.action() == ReplanAction.ASK_HUMAN) {
                    StepAttempt attempt = StepAttempt.start(step, toolCalls, call.id(), call.name());
                    ToolRuntime.ExecutionResult skippedResult = ToolRuntime.ExecutionResult.skipped(
                            "Skipped because an earlier tool call requires human input");
                    observer.onTryStarted(turn, plan, step, attempt, call);
                    messages.add(RuntimeMessage.toolResult(call.id(), skippedResult.value(), true));
                    observer.onTryCompleted(turn, plan, step,
                            attempt.skip("SKIPPED_AFTER_HUMAN_GATE"), call, skippedResult);
                    continue;
                }
                transitionIfPossible(state, PlanPhase.TRYING);
                StepAttempt attempt = StepAttempt.start(step, toolCalls, call.id(), call.name());
                observer.onTryStarted(turn, plan, step, attempt, call);
                ToolRuntime.ExecutionResult result;
                try {
                    result = toolRuntime.execute(call, enabledTools, control);
                } catch (ExecutionCancelledException exception) {
                    result = ToolRuntime.ExecutionResult.cancelled();
                }
                StepAttempt completedAttempt = attempt.finish(result.error(), result.failureType().name());
                RuntimeMessage toolResult = RuntimeMessage.toolResult(call.id(), result.value(), result.error());
                messages.add(toolResult);
                estimatedTokens += estimateTokens(List.of(toolResult));
                observer.onTryCompleted(turn, plan, step, completedAttempt, call, result);

                if (result.error()) {
                    transitionIfPossible(state, PlanPhase.DIAGNOSING);
                    ReplanDecision decision = new DeterministicReplanPolicy(policy.maxReplans()).decide(
                            plan, step, completedAttempt, result,
                            toolRuntime.findReadOnlyAlternative(call.name(), enabledTools), checkpoint);
                    replanDecisions.add(decision);
                    observer.onReplanDecided(plan, decision);
                    pendingDecision = decision;

                    if (decision.action() == ReplanAction.LOCAL_REPLAN) {
                        transitionIfPossible(state, PlanPhase.REPLANNING);
                        plan = plan.replan(decision);
                        transitionIfPossible(state, PlanPhase.PLANNED);
                        observer.onPlanCreated(plan);
                    } else if (decision.action() == ReplanAction.ASK_HUMAN) {
                        transitionIfPossible(state, PlanPhase.AWAITING_CONFIRMATION);
                        observer.onConfirmationRequired(plan, step, decision);
                    } else {
                        transitionIfPossible(state, result.failureType() == ToolRuntime.FailureType.CANCELLED
                                ? PlanPhase.CANCELLED : PlanPhase.FAILED);
                    }
                } else {
                    transitionIfPossible(state, PlanPhase.CHECKPOINTED);
                }

                if (result.fatal() && (pendingDecision == null
                        || pendingDecision.action() != ReplanAction.ASK_HUMAN)) {
                    TerminalReason reason = switch (result.failureType()) {
                        case CANCELLED -> TerminalReason.CANCELLED;
                        case PERMISSION_DENIED -> TerminalReason.PERMISSION_DENIED;
                        default -> TerminalReason.FATAL_TOOL_ERROR;
                    };
                    return terminal(reason, String.valueOf(result.value()), messages,
                            turn, toolCalls, plan, replanDecisions, checkpoint);
                }
            }

            checkpoint = ExecutionCheckpoint.capture(turn, toolCalls, plan, messages);
            observer.onCheckpointCreated(checkpoint);
            if (pendingDecision != null && pendingDecision.action() == ReplanAction.ASK_HUMAN) {
                return terminal(TerminalReason.HUMAN_INPUT_REQUIRED,
                        "Human input is required: " + pendingDecision.reason(), messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint);
            }
        }

        transitionIfPossible(state, PlanPhase.FAILED);
        return terminal(TerminalReason.MAX_TURNS,
                "Agent 已达到最大执行轮数。", messages, policy.maxTurns(), toolCalls,
                plan, replanDecisions, checkpoint);
    }

    private Result terminal(TerminalReason reason, String text, List<RuntimeMessage> messages,
                            int turns, int toolCalls, ExecutionPlan plan,
                            List<ReplanDecision> replanDecisions, ExecutionCheckpoint checkpoint) {
        return new Result(reason, text, List.copyOf(messages), turns, toolCalls,
                plan, List.copyOf(replanDecisions), checkpoint);
    }

    private void transitionIfPossible(PlanStateMachine state, PlanPhase next) {
        if (state.phase() == next) return;
        state.transitionTo(next);
    }

    private String goal(List<RuntimeMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            RuntimeMessage message = messages.get(index);
            if ("user".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "Complete the current agent run";
    }

    private int estimateTokens(List<RuntimeMessage> messages) {
        int characters = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return Math.max(1, (characters + 3) / 4);
    }

    public record RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                            int maxReplans, Duration timeout, BooleanSupplier cancelled) {
        public RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                         Duration timeout, BooleanSupplier cancelled) {
            this(maxTurns, maxToolCalls, maxEstimatedTokens, 2, timeout, cancelled);
        }

        public RunPolicy {
            if (maxTurns < 1 || maxToolCalls < 0 || maxEstimatedTokens < 1 || maxReplans < 0) {
                throw new IllegalArgumentException("Run budgets must be positive");
            }
        }
    }

    public interface RunObserver {
        RunObserver NOOP = new RunObserver() {};
        default void onPlanCreated(ExecutionPlan plan) {}
        default void onTryStarted(int turn, ExecutionPlan plan, PlanStep step, StepAttempt attempt,
                                  RuntimeMessage.ToolCall call) { onToolStarted(turn, call); }
        default void onTryCompleted(int turn, ExecutionPlan plan, PlanStep step, StepAttempt attempt,
                                    RuntimeMessage.ToolCall call, ToolRuntime.ExecutionResult result) {
            onToolCompleted(turn, call, result);
        }
        default void onReplanDecided(ExecutionPlan plan, ReplanDecision decision) {}
        default void onConfirmationRequired(ExecutionPlan plan, PlanStep step, ReplanDecision decision) {}
        default void onCheckpointCreated(ExecutionCheckpoint checkpoint) {}
        default void onCheckpointRestored(ExecutionCheckpoint checkpoint) {}
        default void onModelStarted(int turn) {}
        default void onModelCompleted(int turn, RuntimeMessage message) {}
        default void onToolStarted(int turn, RuntimeMessage.ToolCall call) {}
        default void onToolCompleted(int turn, RuntimeMessage.ToolCall call,
                                     ToolRuntime.ExecutionResult result) {}
    }

    public record Result(TerminalReason reason, String finalText,
                         List<RuntimeMessage> messages, int turns, int toolCalls,
                         ExecutionPlan plan, List<ReplanDecision> replanDecisions,
                         ExecutionCheckpoint checkpoint) {}
}
