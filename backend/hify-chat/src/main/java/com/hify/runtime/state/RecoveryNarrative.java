package com.hify.runtime.state;

import com.hify.runtime.plan.ReplanDecision;
import com.hify.runtime.plan.ExecutionCheckpoint;
import com.hify.runtime.plan.PlanStep;
import com.hify.runtime.plan.StepAttempt;

import java.time.Instant;
import java.util.List;

public record RecoveryNarrative(
        String failurePointId,
        String rootCausePointId,
        String rollbackPointId,
        String replanFromStepId,
        ContinuationAction decision,
        String reason,
        List<String> evidenceIds,
        List<String> gapIds,
        Instant createdAt
) {
    public static RecoveryNarrative from(ReplanDecision replan,
                                         ContinuationDecision continuation) {
        return new RecoveryNarrative(replan.failurePointId(), replan.rootCausePointId(),
                replan.rollbackPointId(), replan.replanFromStepId(), continuation.action(),
                continuation.reason(), continuation.evidenceIds(), continuation.gapIds(), Instant.now());
    }

    public static RecoveryNarrative retry(StepAttempt attempt, PlanStep step,
                                          ExecutionCheckpoint checkpoint,
                                          ContinuationDecision continuation) {
        return new RecoveryNarrative(attempt.id(), step.id(), checkpoint.id(), step.id(),
                continuation.action(), continuation.reason(), continuation.evidenceIds(),
                continuation.gapIds(), Instant.now());
    }
}
