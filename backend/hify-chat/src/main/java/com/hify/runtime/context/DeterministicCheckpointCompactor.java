package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.ArrayList;
import java.util.List;

/** A bounded fallback compactor. It preserves explicit constraints and the newest interaction closure. */
public final class DeterministicCheckpointCompactor implements CheckpointCompactor {
    private static final List<String> CONSTRAINT_MARKERS = List.of(
            "must", "never", "required", "constraint", "必须", "不得", "禁止", "约束", "目标");

    @Override
    public List<RuntimeMessage> compact(List<RuntimeMessage> messages, int targetTokens) {
        if (messages.size() <= 2) return List.copyOf(messages);
        int tailStart = Math.max(1, messages.size() - 2);
        StringBuilder checkpoint = new StringBuilder("[context-checkpoint v1]\n");
        for (int index = 0; index < tailStart; index++) {
            RuntimeMessage message = messages.get(index);
            if (message.content() != null && containsConstraint(message.content())) {
                checkpoint.append(message.role()).append(": ").append(message.content()).append('\n');
            }
        }
        int maxChars = Math.max(64, targetTokens * 2);
        if (checkpoint.length() > maxChars) checkpoint.setLength(maxChars);
        List<RuntimeMessage> result = new ArrayList<>();
        result.add(RuntimeMessage.system(checkpoint.toString()));
        result.addAll(messages.subList(tailStart, messages.size()));
        return List.copyOf(result);
    }

    private boolean containsConstraint(String content) {
        String normalized = content.toLowerCase();
        return CONSTRAINT_MARKERS.stream().anyMatch(normalized::contains);
    }
}
