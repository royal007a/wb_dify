package com.hify.runtime.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PlanStep(
        String id,
        int sequence,
        String action,
        Map<String, Object> input,
        List<String> preconditions,
        String expectedOutcome,
        String risk,
        boolean idempotent,
        boolean reversible
) {
    public PlanStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Step id is required");
        if (sequence < 1) throw new IllegalArgumentException("Step sequence must be positive");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("Step action is required");
        input = Map.copyOf(new LinkedHashMap<>(input == null ? Map.of() : input));
        preconditions = List.copyOf(preconditions == null ? List.of() : preconditions);
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            throw new IllegalArgumentException("Expected outcome is required");
        }
        if (risk == null || risk.isBlank()) throw new IllegalArgumentException("Step risk is required");
    }
}
