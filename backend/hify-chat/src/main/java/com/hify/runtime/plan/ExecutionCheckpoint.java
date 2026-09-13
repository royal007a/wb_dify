package com.hify.runtime.plan;

import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.state.ExecutionContextState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExecutionCheckpoint(
        String id,
        int turn,
        int toolCalls,
        ExecutionPlan plan,
        ExecutionContextState contextState,
        List<RuntimeMessage> messages,
        boolean restorable,
        Instant createdAt
) {
    public ExecutionCheckpoint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Checkpoint id is required");
        if (turn < 0 || toolCalls < 0) throw new IllegalArgumentException("Checkpoint counters cannot be negative");
        if (plan == null) throw new IllegalArgumentException("Checkpoint plan is required");
        if (contextState == null) throw new IllegalArgumentException("Checkpoint context state is required");
        messages = List.copyOf(messages == null ? List.of() : messages);
        if (createdAt == null) throw new IllegalArgumentException("Checkpoint creation time is required");
    }

    public static ExecutionCheckpoint capture(int turn, int toolCalls, ExecutionPlan plan,
                                              ExecutionContextState contextState,
                                              List<RuntimeMessage> messages) {
        return new ExecutionCheckpoint(UUID.randomUUID().toString(), turn, toolCalls,
                plan, contextState, messages, true, Instant.now());
    }

    public String planId() { return plan.id(); }
    public int planVersion() { return plan.version(); }
    public String planDigest() { return plan.digest(); }
    public long evidenceVersion() { return contextState.evidenceVersion(); }
    public long gapVersion() { return contextState.gapVersion(); }
}
