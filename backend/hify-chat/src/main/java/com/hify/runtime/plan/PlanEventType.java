package com.hify.runtime.plan;

public final class PlanEventType {
    public static final String PLAN_CREATED = "plan.created";
    public static final String TRY_STARTED = "step.try.started";
    public static final String TRY_COMPLETED = "step.try.completed";
    public static final String TRY_FAILED = "step.try.failed";
    public static final String CONFIRMATION_REQUIRED = "confirmation.required";
    public static final String CONFIRMATION_ACCEPTED = "confirmation.accepted";
    public static final String CONFIRMATION_CANCELLED = "confirmation.cancelled";
    public static final String REPLAN_DECIDED = "replan.decided";
    public static final String CHECKPOINT_CREATED = "checkpoint.created";
    public static final String CHECKPOINT_RESTORED = "checkpoint.restored";

    private PlanEventType() {}
}
