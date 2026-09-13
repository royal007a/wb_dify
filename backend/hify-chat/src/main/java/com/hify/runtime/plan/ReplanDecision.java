package com.hify.runtime.plan;

import java.util.List;

public record ReplanDecision(
        ReplanAction action,
        String failurePointId,
        String rootCausePointId,
        String rollbackPointId,
        String replanFromStepId,
        String reason,
        List<String> evidence
) {
    public ReplanDecision {
        if (action == null) throw new IllegalArgumentException("Replan action is required");
        if (failurePointId == null || failurePointId.isBlank()) {
            throw new IllegalArgumentException("Failure point is required");
        }
        if (rootCausePointId == null || rootCausePointId.isBlank()) {
            throw new IllegalArgumentException("Root-cause point is required");
        }
        if (rollbackPointId == null || rollbackPointId.isBlank()) {
            throw new IllegalArgumentException("Rollback point is required");
        }
        if (replanFromStepId == null || replanFromStepId.isBlank()) {
            throw new IllegalArgumentException("Replan start is required");
        }
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Replan reason is required");
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }
}
