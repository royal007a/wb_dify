package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lossless for existing system messages and the complete current user turn.
 * Older non-system turns remain in canonical history, not in a synthetic system summary.
 * ContextManager remeasures this projection and rejects it if it cannot fit: never truncate policy.
 */
public final class DeterministicCheckpointCompactor implements CheckpointCompactor {
    @Override
    public List<RuntimeMessage> compact(List<RuntimeMessage> messages, int targetTokens) {
        int turnStart = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role())) {
                turnStart = index;
                break;
            }
        }
        // Without a user boundary we cannot safely infer a complete exchange to discard.
        if (turnStart < 0) return List.copyOf(messages);
        List<RuntimeMessage> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            RuntimeMessage message = messages.get(index);
            if (index >= turnStart || "system".equals(message.role())) result.add(message);
        }
        return List.copyOf(result);
    }
}
