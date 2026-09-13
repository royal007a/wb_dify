package com.hify.runtime.state;

import java.util.List;

public record ClaimState(
        String id,
        String statement,
        Kind kind,
        Status status,
        boolean requiredForCompletion,
        List<String> evidenceIds
) {
    public enum Kind { FACT, INFERENCE }
    public enum Status { UNVERIFIED, VERIFIED, STALE, CONTRADICTED, INVALID }

    public ClaimState {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Claim id is required");
        if (statement == null || statement.isBlank()) throw new IllegalArgumentException("Claim is required");
        if (kind == null || status == null) throw new IllegalArgumentException("Claim kind/status is required");
        evidenceIds = List.copyOf(evidenceIds == null ? List.of() : evidenceIds);
    }
}
