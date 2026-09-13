package com.hify.intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IntentCandidate(
        String intent,
        double confidence,
        IntentRoute route,
        Map<String, Object> slots,
        List<String> missingSlots,
        List<IntentEvidence> evidence,
        String reason
) {
    public IntentCandidate {
        if (intent == null || intent.isBlank()) throw new IllegalArgumentException("Candidate intent is required");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Candidate confidence must be between 0 and 1");
        }
        if (route == null) throw new IllegalArgumentException("Candidate route is required");
        slots = Map.copyOf(new LinkedHashMap<>(slots == null ? Map.of() : slots));
        missingSlots = List.copyOf(missingSlots == null ? List.of() : missingSlots);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        reason = reason == null || reason.isBlank() ? "model_classification" : reason;
    }
}
