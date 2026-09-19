package com.hify.runtime;

import java.util.function.BiPredicate;

/** Runtime identity and ports that must stay stable for one Run. */
public record RunRuntimeIdentity(
        String runId,
        String capabilityRevision,
        String toolSchemaDigest,
        BiPredicate<String, String> executionLeaseValidator,
        HistoryCommitter historyCommitter
) {
    public RunRuntimeIdentity {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run id is required");
        if (capabilityRevision == null || capabilityRevision.isBlank()) {
            throw new IllegalArgumentException("Capability revision is required");
        }
        if (toolSchemaDigest == null || toolSchemaDigest.isBlank()) {
            throw new IllegalArgumentException("Tool schema digest is required");
        }
        executionLeaseValidator = executionLeaseValidator == null ? (attempt, revision) -> true
                : executionLeaseValidator;
        historyCommitter = historyCommitter == null ? HistoryCommitter.NOOP : historyCommitter;
    }

    public static RunRuntimeIdentity local(CapabilitySnapshot snapshot) {
        return new RunRuntimeIdentity("local", snapshot.revision(), snapshot.toolSchemaDigest(),
                (attempt, revision) -> true, HistoryCommitter.NOOP);
    }
}
