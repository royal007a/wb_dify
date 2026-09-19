package com.hify.runtime;

import com.hify.common.ExecutionControl;

import java.util.function.BooleanSupplier;

/** Per-attempt lease revalidated immediately before a tool side effect. */
public record ToolExecutionLease(
        String runId,
        String attemptId,
        String capabilityRevision,
        BooleanSupplier active
) {
    public ToolExecutionLease {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run id is required");
        if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("Attempt id is required");
        if (capabilityRevision == null || capabilityRevision.isBlank()) {
            throw new IllegalArgumentException("Capability revision is required");
        }
        active = active == null ? () -> true : active;
    }

    public void assertUsable(ExecutionControl control) {
        control.throwIfCancelled();
        if (!active.getAsBoolean()) {
            throw new StaleToolExecutionException(
                    "Tool execution lease is no longer active for run " + runId + " attempt " + attemptId);
        }
    }

    public static ToolExecutionLease local(String attemptId, String capabilityRevision) {
        return new ToolExecutionLease("local", attemptId, capabilityRevision, () -> true);
    }
}
