package com.hify.runtime.state;

import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.SuccessCriterion;

public final class FinishGate {
    public ContinuationDecision evaluate(String finalText, ExecutionContextState state, ExecutionPlan plan) {
        for (SuccessCriterion criterion : plan.successCriteria()) {
            boolean satisfied = switch (criterion.kind()) {
                case FINAL_RESPONSE_PRESENT -> finalText != null && !finalText.isBlank();
                case NO_BLOCKING_GAPS -> state.openBlockingGaps().isEmpty();
                case VERIFIED_REQUIRED_CLAIMS -> state.hasVerifiedCoverageForRequiredClaims();
            };
            if (!satisfied) {
                ContinuationAction action = criterion.kind() == SuccessCriterion.Kind.FINAL_RESPONSE_PRESENT
                        ? ContinuationAction.INTERRUPT : ContinuationAction.CLARIFY;
                return ContinuationDecision.of(action,
                        "success_criterion_failed:" + criterion.id(), state, plan.version(), null);
            }
        }
        return ContinuationDecision.of(ContinuationAction.FINISH,
                "success_criteria_satisfied", state, plan.version(), null);
    }
}
