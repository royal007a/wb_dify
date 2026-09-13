package com.hify.runtime.plan;

import com.hify.runtime.ToolRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DeterministicReplanPolicy {
    private final int maxReplans;

    public DeterministicReplanPolicy(int maxReplans) {
        if (maxReplans < 0) throw new IllegalArgumentException("Max replans cannot be negative");
        this.maxReplans = maxReplans;
    }

    public ReplanDecision decide(ExecutionPlan plan, PlanStep step, StepAttempt attempt,
                                 ToolRuntime.ExecutionResult result,
                                 Optional<String> readOnlyAlternative,
                                 ExecutionCheckpoint rollbackPoint) {
        if (!result.error()) throw new IllegalArgumentException("Successful attempts do not require replanning");

        List<String> evidence = new ArrayList<>();
        evidence.add("failureType=" + result.failureType());
        evidence.add("tool=" + attempt.toolName());
        readOnlyAlternative.ifPresent(name -> evidence.add("readOnlyAlternative=" + name));

        ReplanAction action;
        String reason;
        switch (result.failureType()) {
            case INVALID_ARGUMENTS -> {
                action = plan.version() <= maxReplans ? ReplanAction.LOCAL_REPLAN : ReplanAction.ASK_HUMAN;
                reason = action == ReplanAction.LOCAL_REPLAN
                        ? "invalid_tool_arguments_require_local_repair"
                        : "replan_budget_exhausted";
            }
            case TOOL_UNAVAILABLE -> {
                action = readOnlyAlternative.isPresent() && plan.version() <= maxReplans
                        ? ReplanAction.LOCAL_REPLAN : ReplanAction.ASK_HUMAN;
                reason = readOnlyAlternative.isPresent()
                        ? "replace_unavailable_tool_with_read_only_alternative"
                        : "tool_unavailable_without_safe_alternative";
            }
            case PERMISSION_DENIED -> {
                action = ReplanAction.STOP;
                reason = "tool_permission_denied_by_current_policy";
            }
            case CANCELLED -> {
                action = ReplanAction.STOP;
                reason = "run_cancelled";
            }
            default -> {
                action = ReplanAction.STOP;
                reason = "fatal_or_unclassified_tool_failure";
            }
        }

        // The observed attempt and the step that introduced its tool/arguments are deliberately
        // separate coordinates. A later root-cause analyzer may move rootCausePointId further back.
        return new ReplanDecision(action, attempt.id(), step.id(), rollbackPoint.id(),
                step.id(), reason, evidence);
    }
}
