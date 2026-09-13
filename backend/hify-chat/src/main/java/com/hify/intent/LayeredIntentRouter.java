package com.hify.intent;

import com.hify.runtime.ModelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class LayeredIntentRouter {
    private final DeterministicIntentRouter deterministic;
    private final ModelIntentClassifier classifier;
    private final double confidenceThreshold;
    private final double ambiguityMargin;

    public LayeredIntentRouter(DeterministicIntentRouter deterministic,
                               ModelIntentClassifier classifier,
                               @Value("${hify.intent.confidence-threshold:0.75}") double confidenceThreshold,
                               @Value("${hify.intent.ambiguity-margin:0.10}") double ambiguityMargin) {
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("Intent confidence threshold must be between 0 and 1");
        }
        if (ambiguityMargin < 0 || ambiguityMargin > 1) {
            throw new IllegalArgumentException("Intent ambiguity margin must be between 0 and 1");
        }
        this.deterministic = deterministic;
        this.classifier = classifier;
        this.confidenceThreshold = confidenceThreshold;
        this.ambiguityMargin = ambiguityMargin;
    }

    public IntentDecision route(String input, ModelClient modelClient, String model) {
        return route(input, () -> modelClient, model);
    }

    public IntentDecision route(String input, Supplier<ModelClient> modelClient, String model) {
        var deterministicDecision = deterministic.route(input);
        if (deterministicDecision.isPresent()) return deterministicDecision.get();

        String normalized = deterministic.normalize(input);
        List<IntentCandidate> candidates;
        try {
            candidates = new ArrayList<>(classifier.classify(normalized, modelClient.get(), model));
        } catch (RuntimeException modelFailure) {
            return unknown(normalized, "model_classification_failed", List.of(
                    new IntentEvidence("POLICY", "router.fail_closed", modelFailure.getClass().getSimpleName())));
        }
        candidates.removeIf(candidate -> !IntentCatalog.accepts(candidate));
        candidates.sort(Comparator.comparingDouble(IntentCandidate::confidence).reversed());
        if (candidates.isEmpty()) return unknown(normalized, "model_returned_no_valid_candidate", List.of());

        IntentCandidate top = candidates.get(0);
        if (top.route() == IntentRoute.UNKNOWN) {
            return unknown(normalized, top.reason(), top.evidence());
        }
        if (top.confidence() < confidenceThreshold) {
            return clarify(top, normalized, "confidence_below_threshold", candidates, top.missingSlots());
        }
        if (candidates.size() > 1 && top.confidence() - candidates.get(1).confidence() < ambiguityMargin) {
            return clarify(top, normalized, "ambiguous_top_candidates", candidates.subList(0, 2),
                    top.missingSlots());
        }
        List<String> missingSlots = IntentCatalog.missingSlots(top);
        if (!missingSlots.isEmpty()) {
            return clarify(top, normalized, "required_slots_missing", List.of(top), missingSlots);
        }

        try {
            return new IntentDecision(top.intent(), top.confidence(), normalized, top.slots(), List.of(),
                    top.route(), top.evidence(), top.reason());
        } catch (IllegalArgumentException invalidExecutableCandidate) {
            List<String> inferredMissing = switch (top.route()) {
                case TOOL -> List.of("toolName");
                case WORKFLOW -> List.of("workflowId");
                default -> List.of();
            };
            return new IntentDecision(top.intent(), top.confidence(), normalized, top.slots(), inferredMissing,
                    IntentRoute.CLARIFY, top.evidence(), "invalid_executable_candidate");
        }
    }

    private IntentDecision clarify(IntentCandidate candidate, String normalized, String reason,
                                   List<IntentCandidate> compared, List<String> missingSlots) {
        List<IntentEvidence> evidence = new ArrayList<>(candidate.evidence());
        if (compared.size() > 1) {
            evidence.add(new IntentEvidence("POLICY", "router.ambiguity_margin",
                    compared.stream().map(value -> value.intent() + "=" + value.confidence()).toList().toString()));
        }
        return new IntentDecision(candidate.intent(), candidate.confidence(), normalized,
                candidate.slots(), missingSlots, IntentRoute.CLARIFY, evidence, reason);
    }

    private IntentDecision unknown(String normalized, String reason, List<IntentEvidence> evidence) {
        return new IntentDecision("unknown", 0, normalized.isBlank() ? "<empty>" : normalized,
                Map.of(), List.of(), IntentRoute.UNKNOWN, evidence, reason);
    }
}
