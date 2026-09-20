package com.hify.runtime.plan;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class PlanStateMachine {
    private static final Map<PlanPhase, Set<PlanPhase>> ALLOWED = allowedTransitions();
    private PlanPhase phase = PlanPhase.PLANNED;

    public PlanPhase phase() {
        return phase;
    }

    public PlanPhase transitionTo(PlanPhase next) {
        if (!ALLOWED.getOrDefault(phase, Set.of()).contains(next)) {
            throw new IllegalStateException("Illegal plan transition: " + phase + " -> " + next);
        }
        phase = next;
        return phase;
    }

    private static Map<PlanPhase, Set<PlanPhase>> allowedTransitions() {
        Map<PlanPhase, Set<PlanPhase>> transitions = new EnumMap<>(PlanPhase.class);
        transitions.put(PlanPhase.PLANNED,
                EnumSet.of(PlanPhase.TRYING, PlanPhase.COMPLETED, PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.TRYING, EnumSet.of(PlanPhase.AWAITING_CONFIRMATION,
                PlanPhase.EXECUTING, PlanPhase.CHECKPOINTED, PlanPhase.DIAGNOSING,
                PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.AWAITING_CONFIRMATION,
                EnumSet.of(PlanPhase.EXECUTING, PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.EXECUTING,
                EnumSet.of(PlanPhase.CHECKPOINTED, PlanPhase.DIAGNOSING,
                        PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.CHECKPOINTED,
                EnumSet.of(PlanPhase.TRYING, PlanPhase.AWAITING_CONFIRMATION,
                        PlanPhase.COMPLETED, PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.DIAGNOSING,
                EnumSet.of(PlanPhase.REPLANNING, PlanPhase.AWAITING_CONFIRMATION,
                        PlanPhase.CANCELLED, PlanPhase.FAILED));
        transitions.put(PlanPhase.REPLANNING,
                EnumSet.of(PlanPhase.PLANNED, PlanPhase.AWAITING_CONFIRMATION,
                        PlanPhase.CANCELLED, PlanPhase.FAILED));
        return Map.copyOf(transitions);
    }
}
