package com.hify.runtime.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public record ExecutionPlan(
        String id,
        int version,
        String goal,
        List<PlanStep> steps,
        String supersedesPlanId,
        String triggerAttemptId,
        String digest
) {
    public ExecutionPlan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Plan id is required");
        if (version < 1) throw new IllegalArgumentException("Plan version must be positive");
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("Plan goal is required");
        steps = List.copyOf(steps == null ? List.of() : steps);
        if (digest == null || digest.isBlank()) throw new IllegalArgumentException("Plan digest is required");
    }

    public static ExecutionPlan initial(String goal) {
        String id = UUID.randomUUID().toString();
        PlanStep step = new PlanStep(UUID.randomUUID().toString(), 1, "model_decision",
                java.util.Map.of("goal", goal), List.of("run budgets remain available"),
                "Resolve the user goal", "read", true, true);
        return new ExecutionPlan(id, 1, goal, List.of(step), null, null,
                digest(1, goal, List.of(step), null));
    }

    public ExecutionPlan replan(ReplanDecision decision) {
        int nextVersion = version + 1;
        String nextId = UUID.randomUUID().toString();
        PlanStep repair = new PlanStep(UUID.randomUUID().toString(), 1,
                switch (decision.action()) {
                    case LOCAL_REPLAN -> "repair_current_step";
                    case SUFFIX_REPLAN -> "rebuild_remaining_steps";
                    case FULL_REPLAN -> "rebuild_plan";
                    default -> "continue_plan";
                },
                java.util.Map.of("reason", decision.reason(),
                        "replanFromStepId", decision.replanFromStepId()),
                List.of("rollback checkpoint remains valid", "replan budget remains available"),
                "Resolve the failure without repeating the same attempt", "read", true, true);
        return new ExecutionPlan(nextId, nextVersion, goal, List.of(repair), id,
                decision.failurePointId(), digest(nextVersion, goal, List.of(repair), id));
    }

    private static String digest(int version, String goal, List<PlanStep> steps, String parent) {
        try {
            String canonicalSteps = steps.stream().map(ExecutionPlan::canonicalStep)
                    .collect(java.util.stream.Collectors.joining("\n"));
            String canonical = version + "\n" + goal + "\n" + String.valueOf(parent) + "\n" + canonicalSteps;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not digest execution plan", exception);
        }
    }

    private static String canonicalStep(PlanStep step) {
        String inputs = step.input().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return String.join("|", step.id(), String.valueOf(step.sequence()), step.action(), inputs,
                String.join(",", step.preconditions()), step.expectedOutcome(), step.risk(),
                String.valueOf(step.idempotent()), String.valueOf(step.reversible()));
    }
}
