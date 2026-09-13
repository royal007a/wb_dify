package com.hify.runtime.plan;

public enum PlanPhase {
    PLANNED,
    TRYING,
    AWAITING_CONFIRMATION,
    EXECUTING,
    CHECKPOINTED,
    DIAGNOSING,
    REPLANNING,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
