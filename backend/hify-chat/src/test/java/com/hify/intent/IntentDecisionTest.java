package com.hify.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentDecisionTest {
    @Test
    void rejectsConfidenceOutsideContract() {
        assertThatThrownBy(() -> decision("unknown", 1.1, IntentRoute.UNKNOWN, Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Confidence");
    }

    @Test
    void rejectsExecutableRouteWithMissingSlots() {
        assertThatThrownBy(() -> decision("calculate", 0.9, IntentRoute.TOOL,
                Map.of("toolName", "calculator"), List.of("expression")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing slots");
    }

    @Test
    void requiresToolAndWorkflowRoutingKeys() {
        assertThatThrownBy(() -> decision("calculate", 0.9, IntentRoute.TOOL, Map.of(), List.of()))
                .hasMessageContaining("toolName");
        assertThatThrownBy(() -> decision("run_workflow", 0.9, IntentRoute.WORKFLOW, Map.of(), List.of()))
                .hasMessageContaining("workflowId");
    }

    @Test
    void unknownRouteCannotMasqueradeAsKnownIntent() {
        assertThatThrownBy(() -> decision("calculate", 0.2, IntentRoute.UNKNOWN, Map.of(), List.of()))
                .hasMessageContaining("unknown intent");
    }

    private IntentDecision decision(String intent, double confidence, IntentRoute route,
                                    Map<String, Object> slots, List<String> missingSlots) {
        return new IntentDecision(intent, confidence, "input", slots, missingSlots, route,
                List.of(), "test");
    }
}
