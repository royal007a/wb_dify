package com.hify.runtime.plan;

import com.hify.runtime.RuntimeMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExecutionCheckpoint(
        String id,
        int turn,
        int toolCalls,
        ExecutionPlan plan,
        List<RuntimeMessage> messages,
        boolean restorable,
        Instant createdAt
) {
    public ExecutionCheckpoint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Checkpoint id is required");
        if (turn < 0 || toolCalls < 0) throw new IllegalArgumentException("Checkpoint counters cannot be negative");
        if (plan == null) throw new IllegalArgumentException("Checkpoint plan is required");
        messages = List.copyOf(messages == null ? List.of() : messages);
        if (createdAt == null) throw new IllegalArgumentException("Checkpoint creation time is required");
    }

    public static ExecutionCheckpoint capture(int turn, int toolCalls, ExecutionPlan plan,
                                              List<RuntimeMessage> messages) {
        return new ExecutionCheckpoint(UUID.randomUUID().toString(), turn, toolCalls,
                plan, messages, true, Instant.now());
    }

    public String planId() { return plan.id(); }
    public int planVersion() { return plan.version(); }
    public String planDigest() { return plan.digest(); }
}
