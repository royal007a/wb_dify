package com.hify.runtime.plan;

import java.time.Instant;
import java.util.UUID;

public record StepAttempt(
        String id,
        String stepId,
        int attemptNo,
        String toolCallId,
        String toolName,
        Status status,
        String failureClass,
        Instant startedAt,
        Instant finishedAt
) {
    public enum Status { RUNNING, SUCCEEDED, FAILED, SKIPPED }

    public StepAttempt {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Attempt id is required");
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("Step id is required");
        if (attemptNo < 1) throw new IllegalArgumentException("Attempt number must be positive");
        if (toolCallId == null || toolCallId.isBlank()) throw new IllegalArgumentException("Tool call id is required");
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("Tool name is required");
        if (status == null) throw new IllegalArgumentException("Attempt status is required");
        if (startedAt == null) throw new IllegalArgumentException("Attempt start time is required");
    }

    public static StepAttempt start(PlanStep step, int attemptNo, String toolCallId, String toolName) {
        return new StepAttempt(UUID.randomUUID().toString(), step.id(), attemptNo,
                toolCallId, toolName, Status.RUNNING, null, Instant.now(), null);
    }

    public StepAttempt finish(boolean failed, String failureClass) {
        return new StepAttempt(id, stepId, attemptNo, toolCallId, toolName,
                failed ? Status.FAILED : Status.SUCCEEDED,
                failed ? failureClass : null, startedAt, Instant.now());
    }

    public StepAttempt skip(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Skip reason is required");
        return new StepAttempt(id, stepId, attemptNo, toolCallId, toolName,
                Status.SKIPPED, reason, startedAt, Instant.now());
    }
}
