package com.hify.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class IntentCatalog {
    private static final Map<String, IntentSpec> SPECS = Map.of(
            "show_help", new IntentSpec(IntentRoute.TOOL, "show_help", List.of("toolName")),
            "cancel_run", new IntentSpec(IntentRoute.TOOL, "cancel_run", List.of("toolName")),
            "current_time", new IntentSpec(IntentRoute.TOOL, "current_time", List.of("toolName")),
            "calculate", new IntentSpec(IntentRoute.TOOL, "calculator", List.of("toolName", "expression")),
            "run_workflow", new IntentSpec(IntentRoute.WORKFLOW, null, List.of("workflowId")),
            "unknown", new IntentSpec(IntentRoute.UNKNOWN, null, List.of())
    );

    private IntentCatalog() {}

    static boolean accepts(IntentCandidate candidate) {
        IntentSpec spec = SPECS.get(candidate.intent());
        if (spec == null) return false;
        if (candidate.route() != IntentRoute.CLARIFY && candidate.route() != spec.route()) return false;
        if (spec.toolName() != null && candidate.slots().containsKey("toolName")
                && !spec.toolName().equals(candidate.slots().get("toolName"))) return false;
        return true;
    }

    static List<String> missingSlots(IntentCandidate candidate) {
        IntentSpec spec = SPECS.get(candidate.intent());
        if (spec == null) return List.of();
        LinkedHashSet<String> missing = new LinkedHashSet<>(candidate.missingSlots());
        for (String required : spec.requiredSlots()) {
            Object value = candidate.slots().get(required);
            if (!(value instanceof String text) || text.isBlank()) missing.add(required);
        }
        return List.copyOf(missing);
    }

    record IntentSpec(IntentRoute route, String toolName, List<String> requiredSlots) {}
}
