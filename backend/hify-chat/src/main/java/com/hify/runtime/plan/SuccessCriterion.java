package com.hify.runtime.plan;

public record SuccessCriterion(String id, Kind kind, String description) {
    public enum Kind {
        FINAL_RESPONSE_PRESENT,
        NO_BLOCKING_GAPS,
        VERIFIED_REQUIRED_CLAIMS
    }

    public SuccessCriterion {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Criterion id is required");
        if (kind == null) throw new IllegalArgumentException("Criterion kind is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Criterion description is required");
        }
    }
}
