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
import com.hify.runtime.state.ContinuationAction;
import com.hify.runtime.state.ContinuationDecision;
import com.hify.runtime.state.ExecutionContextState;
import com.hify.runtime.state.FinishGate;
import com.hify.runtime.state.GapState;
import com.hify.runtime.state.RecoveryNarrative;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
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
        CapabilitySnapshot capability = toolRuntime.snapshot("local", enabledTools);
        return run(initialMessages, modelClient, model, temperature, capability,
                policy, observer, RunRuntimeIdentity.local(capability));
    }

    public Result run(List<RuntimeMessage> initialMessages, ModelClient modelClient,
                      String model, double temperature, CapabilitySnapshot capability,
                      RunPolicy policy, RunObserver observer, RunRuntimeIdentity identity) {
        ExecutionPlan plan = ExecutionPlan.initial(goal(initialMessages));
        return run(initialMessages, modelClient, model, temperature, capability,
                policy, observer, identity, plan, ExecutionContextState.empty(), 0, 0, false);
    }

    public Result resume(ExecutionCheckpoint checkpoint, ModelClient modelClient,
                         String model, double temperature, Set<String> enabledTools,
                         RunPolicy policy, RunObserver observer) {
        CapabilitySnapshot capability = toolRuntime.snapshot("local", enabledTools);
        return resume(checkpoint, modelClient, model, temperature, capability,
                policy, observer, RunRuntimeIdentity.local(capability));
    }

    public Result resume(ExecutionCheckpoint checkpoint, ModelClient modelClient,
                         String model, double temperature, CapabilitySnapshot capability,
                         RunPolicy policy, RunObserver observer, RunRuntimeIdentity identity) {
        ExecutionPlan plan = checkpoint.plan();
        observer.onCheckpointRestored(checkpoint);
        return run(checkpoint.messages(), modelClient, model, temperature, capability,
                policy, observer, identity, plan, checkpoint.contextState(),
                checkpoint.turn(), checkpoint.toolCalls(), true);
    }

    private Result run(List<RuntimeMessage> initialMessages, ModelClient modelClient,
                       String model, double temperature, CapabilitySnapshot capability,
                       RunPolicy policy, RunObserver observer, RunRuntimeIdentity identity,
                       ExecutionPlan initialPlan,
                       ExecutionContextState initialContext,
                       int completedTurns, int completedToolCalls, boolean resumed) {
        List<RuntimeMessage> messages = new ArrayList<>(initialMessages);
        Set<String> seenToolCallIds = existingToolCallIds(initialMessages);
        ExecutionControl control = ExecutionControl.withTimeout(policy.timeout(), policy.cancelled());
        int toolCalls = completedToolCalls;
        int estimatedTokens = estimateTokens(messages);
        ExecutionPlan plan = initialPlan;
        ExecutionContextState contextState = initialContext;
        PlanStateMachine state = new PlanStateMachine();
        List<ReplanDecision> replanDecisions = new ArrayList<>();
        ExecutionCheckpoint checkpoint = ExecutionCheckpoint.capture(
                completedTurns, toolCalls, plan, contextState, messages);
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
                int currentTurn = turn;
                response = modelClient.generateStream(new ModelRequest(
                        model, temperature, List.copyOf(messages),
                        capability.definitions(), control),
                        delta -> observer.onModelDelta(currentTurn, delta));
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
            commitHistory(identity, "model:" + turn, messages, observer);
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
                ContinuationDecision finish = new FinishGate().evaluate(response.content(), contextState, plan);
                observer.onContinuationDecided(finish);
                if (finish.action() == ContinuationAction.FINISH) {
                    transitionIfPossible(state, PlanPhase.COMPLETED);
                    return terminal(TerminalReason.COMPLETED, response.content(), messages,
                            turn, toolCalls, plan, replanDecisions, checkpoint, finish);
                }
                if (finish.action() == ContinuationAction.CLARIFY) {
                    transitionIfPossible(state, PlanPhase.AWAITING_CONFIRMATION);
                    return terminal(TerminalReason.HUMAN_INPUT_REQUIRED,
                            "Human input is required: " + finish.reason(), messages,
                            turn, toolCalls, plan, replanDecisions, checkpoint, finish);
                }
                transitionIfPossible(state, PlanPhase.FAILED);
                return terminal(TerminalReason.FATAL_TOOL_ERROR, finish.reason(), messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint, finish);
            }

            ContinuationDecision pendingContinuation = null;
            TerminalReason pendingTerminalReason = null;
            String pendingTerminalText = null;
            for (RuntimeMessage.ToolCall call : response.toolCalls()) {
                if (!seenToolCallIds.add(call.id())) {
                    transitionIfPossible(state, PlanPhase.FAILED);
                    return terminal(TerminalReason.DUPLICATE_TOOL_CALL,
                            "Duplicate tool-call id: " + call.id(), messages, turn, toolCalls,
                            plan, replanDecisions, checkpoint);
                }
                PlanStep step = plan.steps().get(0);
                if (pendingContinuation != null && (pendingContinuation.action() == ContinuationAction.CLARIFY
                        || pendingContinuation.action() == ContinuationAction.INTERRUPT)) {
                    StepAttempt skipped = StepAttempt.start(step, toolCalls + 1, call.id(), call.name());
                    ToolRuntime.ExecutionResult skippedResult = ToolRuntime.ExecutionResult.skipped(
                            "Skipped because an earlier action stopped this TAO turn");
                    observer.onTryStarted(turn, plan, step, skipped, call);
                    messages.add(RuntimeMessage.toolResult(call.id(), skippedResult.value(), true));
                    commitHistory(identity, "tool:" + call.id(), messages, observer);
                    observer.onTryCompleted(turn, plan, step,
                            skipped.skip("SKIPPED_AFTER_DECISION_GATE"), call, skippedResult);
                    continue;
                }
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

                ToolRuntime.ExecutionResult result;
                StepAttempt completedAttempt;
                int retries = 0;
                while (true) {
                    transitionIfPossible(state, PlanPhase.TRYING);
                    toolCalls++;
                    StepAttempt attempt = StepAttempt.start(step, toolCalls, call.id(), call.name());
                    observer.onTryStarted(turn, plan, step, attempt, call);
                    try {
                        ToolExecutionLease lease = new ToolExecutionLease(identity.runId(), attempt.id(),
                                identity.capabilityRevision(), () -> identity.executionLeaseValidator()
                                .test(attempt.id(), identity.capabilityRevision()));
                        result = toolRuntime.execute(call, capability, lease, control);
                    } catch (ExecutionCancelledException exception) {
                        result = ToolRuntime.ExecutionResult.cancelled();
                    }
                    completedAttempt = attempt.finish(result.error(), result.failureType().name());
                    observer.onTryCompleted(turn, plan, step, completedAttempt, call, result);
                    contextState = contextState.recordToolResult(plan, completedAttempt, result);

                    if (result.failureType() == ToolRuntime.FailureType.TRANSIENT
                            && retries < policy.maxRetries() && toolCalls < policy.maxToolCalls()) {
                        retries++;
                        ContinuationDecision retry = ContinuationDecision.of(ContinuationAction.RETRY,
                                "transient_tool_failure", contextState, plan.version(), completedAttempt.id());
                        observer.onContinuationDecided(retry);
                        observer.onRecoveryNarrated(RecoveryNarrative.retry(
                                completedAttempt, step, checkpoint, retry));
                        observer.onContextStateChanged(contextState);
                        continue;
                    }
                    break;
                }

                RuntimeMessage toolResult = RuntimeMessage.toolResult(call.id(), result.value(), result.error());
                messages.add(toolResult);
                estimatedTokens += estimateTokens(List.of(toolResult));
                commitHistory(identity, "tool:" + call.id(), messages, observer);

                if (result.error()) {
                    java.util.Optional<String> alternative =
                            toolRuntime.findReadOnlyAlternative(call.name(), capability.enabledTools());
                    if (result.failureType() != ToolRuntime.FailureType.CANCELLED) {
                        contextState = contextState.openGap(gapFor(result, completedAttempt, alternative));
                    }
                    contextState = contextState.observeProgress();
                    observer.onContextStateChanged(contextState);
                    transitionIfPossible(state, PlanPhase.DIAGNOSING);

                    if (result.failureType() != ToolRuntime.FailureType.TRANSIENT
                            && contextState.hasNoProgress(2)) {
                        contextState = contextState.openGap(GapState.open(GapState.Kind.NO_PROGRESS,
                                "Repeated action produced no new evidence", true, "user:clarification",
                                List.of("clarify_goal", "provide_missing_input", "interrupt"),
                                completedAttempt.id()));
                        observer.onContextStateChanged(contextState);
                        ReplanDecision noProgress = new ReplanDecision(ReplanAction.ASK_HUMAN,
                                completedAttempt.id(), step.id(), checkpoint.id(), step.id(),
                                "no_progress_detected", contextState.evidenceIds());
                        replanDecisions.add(noProgress);
                        observer.onReplanDecided(plan, noProgress);
                        pendingContinuation = ContinuationDecision.of(ContinuationAction.CLARIFY,
                                "no_progress_detected", contextState, plan.version(), completedAttempt.id());
                        observer.onContinuationDecided(pendingContinuation);
                        observer.onRecoveryNarrated(RecoveryNarrative.from(noProgress, pendingContinuation));
                        transitionIfPossible(state, PlanPhase.AWAITING_CONFIRMATION);
                        observer.onConfirmationRequired(plan, step, noProgress);
                        continue;
                    }

                    if (result.failureType() == ToolRuntime.FailureType.TRANSIENT) {
                        pendingContinuation = ContinuationDecision.of(ContinuationAction.INTERRUPT,
                                "retry_budget_exhausted", contextState, plan.version(), completedAttempt.id());
                        observer.onContinuationDecided(pendingContinuation);
                        observer.onRecoveryNarrated(RecoveryNarrative.retry(
                                completedAttempt, step, checkpoint, pendingContinuation));
                        transitionIfPossible(state, PlanPhase.FAILED);
                        pendingTerminalReason = TerminalReason.RETRY_EXHAUSTED;
                        pendingTerminalText = String.valueOf(result.value());
                        continue;
                    }

                    ReplanDecision decision = new DeterministicReplanPolicy(policy.maxReplans()).decide(
                            plan, step, completedAttempt, result, alternative, checkpoint);
                    replanDecisions.add(decision);
                    observer.onReplanDecided(plan, decision);
                    ContinuationAction action = switch (decision.action()) {
                        case RETRY -> ContinuationAction.RETRY;
                        case LOCAL_REPLAN, SUFFIX_REPLAN, FULL_REPLAN -> ContinuationAction.REPLAN;
                        case ASK_HUMAN -> ContinuationAction.CLARIFY;
                        case STOP -> ContinuationAction.INTERRUPT;
                    };
                    pendingContinuation = ContinuationDecision.of(action, decision.reason(),
                            contextState, plan.version(), completedAttempt.id());
                    observer.onContinuationDecided(pendingContinuation);
                    observer.onRecoveryNarrated(RecoveryNarrative.from(decision, pendingContinuation));

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
                        pendingTerminalReason = switch (result.failureType()) {
                            case CANCELLED -> TerminalReason.CANCELLED;
                            case PERMISSION_DENIED -> TerminalReason.PERMISSION_DENIED;
                            default -> TerminalReason.FATAL_TOOL_ERROR;
                        };
                        pendingTerminalText = String.valueOf(result.value());
                    }
                } else {
                    contextState = contextState.observeProgress();
                    observer.onContextStateChanged(contextState);
                    pendingContinuation = ContinuationDecision.of(ContinuationAction.CONTINUE,
                            "verified_tool_result_recorded", contextState,
                            plan.version(), completedAttempt.id());
                    observer.onContinuationDecided(pendingContinuation);
                    transitionIfPossible(state, PlanPhase.CHECKPOINTED);
                }
            }

            checkpoint = ExecutionCheckpoint.capture(turn, toolCalls, plan, contextState, messages);
            observer.onCheckpointCreated(checkpoint);
            if (pendingContinuation != null && pendingContinuation.action() == ContinuationAction.CLARIFY) {
                return terminal(TerminalReason.HUMAN_INPUT_REQUIRED,
                        "Human input is required: " + pendingContinuation.reason(), messages,
                        turn, toolCalls, plan, replanDecisions, checkpoint, pendingContinuation);
            }
            if (pendingContinuation != null && pendingContinuation.action() == ContinuationAction.INTERRUPT) {
                return terminal(pendingTerminalReason == null ? TerminalReason.FATAL_TOOL_ERROR : pendingTerminalReason,
                        pendingTerminalText == null ? pendingContinuation.reason() : pendingTerminalText,
                        messages, turn, toolCalls, plan, replanDecisions, checkpoint, pendingContinuation);
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
        ContinuationAction action = switch (reason) {
            case COMPLETED -> ContinuationAction.FINISH;
            case HUMAN_INPUT_REQUIRED -> ContinuationAction.CLARIFY;
            default -> ContinuationAction.INTERRUPT;
        };
        return terminal(reason, text, messages, turns, toolCalls, plan, replanDecisions,
                checkpoint, ContinuationDecision.of(action, "terminal:" + reason.name(),
                        checkpoint.contextState(), plan.version(), null));
    }

    private Result terminal(TerminalReason reason, String text, List<RuntimeMessage> messages,
                            int turns, int toolCalls, ExecutionPlan plan,
                            List<ReplanDecision> replanDecisions, ExecutionCheckpoint checkpoint,
                            ContinuationDecision finalDecision) {
        return new Result(reason, text, List.copyOf(messages), turns, toolCalls,
                plan, List.copyOf(replanDecisions), checkpoint,
                checkpoint.contextState(), finalDecision);
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

    private GapState gapFor(ToolRuntime.ExecutionResult result, StepAttempt attempt,
                            java.util.Optional<String> alternative) {
        return switch (result.failureType()) {
            case INVALID_ARGUMENTS -> GapState.open(GapState.Kind.INVALID_INPUT,
                    String.valueOf(result.value()), true, "tool:" + attempt.toolName(),
                    List.of("repair_arguments", "ask_user"), attempt.id());
            case TOOL_UNAVAILABLE -> GapState.open(GapState.Kind.MISSING_CAPABILITY,
                    String.valueOf(result.value()), true,
                    "tool:" + alternative.orElse(attempt.toolName()),
                    alternative.<List<String>>map(name -> List.of("use_tool:" + name))
                            .orElseGet(() -> List.of("ask_user", "interrupt")), attempt.id());
            case PERMISSION_DENIED, STALE_LEASE -> GapState.open(GapState.Kind.MISSING_PERMISSION,
                    String.valueOf(result.value()), true, "permission:" + attempt.toolName(),
                    List.of("request_permission", "interrupt"), attempt.id());
            case TRANSIENT -> GapState.open(GapState.Kind.TRANSIENT_FAILURE,
                    String.valueOf(result.value()), true, "tool:" + attempt.toolName(),
                    List.of("retry", "interrupt"), attempt.id());
            case EXECUTION_FAILED -> GapState.open(GapState.Kind.UNVERIFIED_CLAIM,
                    String.valueOf(result.value()), true, "tool:" + attempt.toolName(),
                    List.of("replan", "interrupt"), attempt.id());
            case CANCELLED, NONE -> throw new IllegalArgumentException(
                    "Failure type does not create a gap: " + result.failureType());
        };
    }

    private int estimateTokens(List<RuntimeMessage> messages) {
        int characters = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return Math.max(1, (characters + 3) / 4);
    }

    private Set<String> existingToolCallIds(List<RuntimeMessage> messages) {
        Set<String> ids = new HashSet<>();
        for (RuntimeMessage message : messages) {
            if (message.toolCalls() != null) message.toolCalls().forEach(call -> ids.add(call.id()));
        }
        return ids;
    }

    private void commitHistory(RunRuntimeIdentity identity, String operationId,
                               List<RuntimeMessage> messages, RunObserver observer) {
        HistoryCommitter.CommitReceipt receipt = identity.historyCommitter()
                .commit(operationId, List.copyOf(messages));
        observer.onHistoryCommitted(operationId, receipt);
    }

    public record RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                            int maxReplans, int maxRetries,
                            Duration timeout, BooleanSupplier cancelled) {
        public RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                         Duration timeout, BooleanSupplier cancelled) {
            this(maxTurns, maxToolCalls, maxEstimatedTokens, 2, 1, timeout, cancelled);
        }

        public RunPolicy(int maxTurns, int maxToolCalls, int maxEstimatedTokens,
                         int maxReplans, Duration timeout, BooleanSupplier cancelled) {
            this(maxTurns, maxToolCalls, maxEstimatedTokens, maxReplans, 1, timeout, cancelled);
        }

        public RunPolicy {
            if (maxTurns < 1 || maxToolCalls < 0 || maxEstimatedTokens < 1
                    || maxReplans < 0 || maxRetries < 0) {
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
        default void onContinuationDecided(ContinuationDecision decision) {}
        default void onContextStateChanged(ExecutionContextState contextState) {}
        default void onRecoveryNarrated(RecoveryNarrative narrative) {}
        default void onConfirmationRequired(ExecutionPlan plan, PlanStep step, ReplanDecision decision) {}
        default void onCheckpointCreated(ExecutionCheckpoint checkpoint) {}
        default void onCheckpointRestored(ExecutionCheckpoint checkpoint) {}
        default void onModelStarted(int turn) {}
        default void onModelDelta(int turn, String delta) {}
        default void onModelCompleted(int turn, RuntimeMessage message) {}
        default void onHistoryCommitted(String operationId, HistoryCommitter.CommitReceipt receipt) {}
        default void onToolStarted(int turn, RuntimeMessage.ToolCall call) {}
        default void onToolCompleted(int turn, RuntimeMessage.ToolCall call,
                                     ToolRuntime.ExecutionResult result) {}
    }

    public record Result(TerminalReason reason, String finalText,
                         List<RuntimeMessage> messages, int turns, int toolCalls,
                         ExecutionPlan plan, List<ReplanDecision> replanDecisions,
                         ExecutionCheckpoint checkpoint, ExecutionContextState contextState,
                         ContinuationDecision finalDecision) {}
}
