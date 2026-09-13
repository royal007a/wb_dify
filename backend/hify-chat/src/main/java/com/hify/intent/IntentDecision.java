package com.hify.intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IntentDecision(
        String intent,
        double confidence,
        String normalizedInput,
        Map<String, Object> slots,
        List<String> missingSlots,
        IntentRoute route,
        List<IntentEvidence> evidence,
        String reason
) {
    public IntentDecision {
        if (intent == null || intent.isBlank()) throw new IllegalArgumentException("Intent is required");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
        if (normalizedInput == null || normalizedInput.isBlank()) {
            throw new IllegalArgumentException("Normalized input is required");
        }
        if (route == null) throw new IllegalArgumentException("Route is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Decision reason is required");

        slots = Map.copyOf(new LinkedHashMap<>(slots == null ? Map.of() : slots));
        missingSlots = List.copyOf(missingSlots == null ? List.of() : missingSlots);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);

        if (route == IntentRoute.UNKNOWN && !"unknown".equals(intent)) {
            throw new IllegalArgumentException("Unknown route must use the unknown intent");
        }
        if ((route == IntentRoute.TOOL || route == IntentRoute.WORKFLOW) && !missingSlots.isEmpty()) {
            throw new IllegalArgumentException("Executable routes cannot contain missing slots");
        }
        if (route == IntentRoute.TOOL && !hasText(slots, "toolName")) {
            throw new IllegalArgumentException("Tool route requires the toolName slot");
        }
        if (route == IntentRoute.WORKFLOW && !hasText(slots, "workflowId")) {
            throw new IllegalArgumentException("Workflow route requires the workflowId slot");
        }
    }

    private static boolean hasText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof String text && !text.isBlank();
    }
}
