package com.hify.runtime.state;

import com.hify.runtime.ToolRuntime;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.StepAttempt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextStateTest {
    @Test
    void finishGateEvaluatesExecutablePlanCriteria() {
        ExecutionPlan plan = ExecutionPlan.initial("calculate 6*7");
        StepAttempt attempt = StepAttempt.start(plan.steps().get(0), 1, "call-1", "calculator")
                .finish(false, "NONE");
        ExecutionContextState state = ExecutionContextState.empty()
                .recordToolResult(plan, attempt, ToolRuntime.ExecutionResult.success("42"));

        ContinuationDecision decision = new FinishGate().evaluate("42", state, plan);

        assertThat(plan.successCriteria()).hasSize(3);
        assertThat(decision.action()).isEqualTo(ContinuationAction.FINISH);
        assertThat(state.hasVerifiedCoverageForRequiredClaims()).isTrue();
    }

    @Test
    void finishGateRejectsOpenGapAndMissingEvidenceCoverage() {
        ExecutionPlan plan = ExecutionPlan.initial("answer with evidence");
        GapState gap = GapState.open(GapState.Kind.MISSING_INPUT, "date is required",
                true, "user:date", List.of("ask_user"), null);
        ExecutionContextState withGap = ExecutionContextState.empty().openGap(gap);
        assertThat(new FinishGate().evaluate("answer", withGap, plan).action())
                .isEqualTo(ContinuationAction.CLARIFY);

        ClaimState unsupported = new ClaimState("claim-1", "unsupported conclusion",
                ClaimState.Kind.FACT, ClaimState.Status.UNVERIFIED, true, List.of());
        ExecutionContextState withoutCoverage = new ExecutionContextState(1, 0,
                List.of(unsupported), List.of(), List.of(), null, 0);
        assertThat(new FinishGate().evaluate("answer", withoutCoverage, plan).reason())
                .isEqualTo("success_criterion_failed:verified-required-claims");
    }

    @Test
    void deduplicatesGapsAndDetectsRepeatedStateWithoutNewEvidence() {
        GapState first = GapState.open(GapState.Kind.INVALID_INPUT, "expression is required",
                true, "tool:calculator", List.of("repair_arguments"), "attempt-1");
        GapState duplicate = GapState.open(GapState.Kind.INVALID_INPUT, "expression is required",
                true, "tool:calculator", List.of("repair_arguments"), "attempt-2");

        ExecutionContextState state = ExecutionContextState.empty().openGap(first).observeProgress();
        ExecutionContextState repeated = state.openGap(duplicate).observeProgress();

        assertThat(repeated.gaps()).hasSize(1);
        assertThat(repeated.gapVersion()).isEqualTo(1);
        assertThat(repeated.hasNoProgress(2)).isTrue();
    }
}
