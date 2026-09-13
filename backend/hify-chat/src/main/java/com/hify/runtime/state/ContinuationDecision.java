package com.hify.runtime.state;

import java.time.Instant;
import java.util.List;

public record ContinuationDecision(
        ContinuationAction action,
        String reason,
        List<String> evidenceIds,
        List<String> gapIds,
        int planVersion,
        String attemptId,
        Instant createdAt
) {
    public ContinuationDecision {
        if (action == null) throw new IllegalArgumentException("Continuation action is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Decision reason is required");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
        gapIds = List.copyOf(gapIds == null ? List.of() : gapIds);
        if (planVersion < 1) throw new IllegalArgumentException("Plan version must be positive");
        if (createdAt == null) throw new IllegalArgumentException("Decision time is required");
    }

    public static ContinuationDecision of(ContinuationAction action, String reason,
                                          ExecutionContextState state, int planVersion,
                                          String attemptId) {
        return new ContinuationDecision(action, reason, state.evidenceIds(),
                state.openBlockingGapIds(), planVersion, attemptId, Instant.now());
    }
}
