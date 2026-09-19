package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Quality metrics intentionally cover semantic safety, not only token reduction. */
public final class ContextQualityEvaluator {
    public Metrics evaluate(ContextManager.PreparedContext context, List<String> criticalConstraints) {
        String text = context.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .reduce("", (left, right) -> left + "\n" + right);
        long retained = criticalConstraints.stream().filter(text::contains).count();
        double constraintRetention = criticalConstraints.isEmpty() ? 1d
                : (double) retained / criticalConstraints.size();

        Set<String> seenCalls = new HashSet<>();
        int calls = 0;
        int duplicateCalls = 0;
        for (RuntimeMessage message : context.messages()) {
            if (message.toolCalls() == null) continue;
            for (RuntimeMessage.ToolCall call : message.toolCalls()) {
                calls++;
                String signature = call.name() + ":" + call.arguments();
                if (!seenCalls.add(signature)) duplicateCalls++;
            }
        }
        double duplicateInvestigationRate = calls == 0 ? 0d : (double) duplicateCalls / calls;
        double recoverySuccessRate = context.archiveReferences().stream()
                .allMatch(reference -> reference.originalContent() != null
                        && !reference.originalContent().isBlank()) ? 1d : 0d;
        return new Metrics(constraintRetention, duplicateInvestigationRate,
                recoverySuccessRate, context.tokenReductionRate());
    }

    public record Metrics(double constraintRetentionRate, double duplicateInvestigationRate,
                          double recoverySuccessRate, double tokenReductionRate) {}
}
