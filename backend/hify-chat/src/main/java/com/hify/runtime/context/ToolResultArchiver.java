package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces a model-facing projection. Raw tool results remain in canonical Run history;
 * the stable toolCallId is the recovery pointer.
 */
public final class ToolResultArchiver {
    public Result archive(List<RuntimeMessage> messages, int contentThreshold) {
        List<RuntimeMessage> projected = new ArrayList<>(messages.size());
        List<ArchiveReference> references = new ArrayList<>();
        for (RuntimeMessage message : messages) {
            if ("tool".equals(message.role()) && message.content() != null
                    && message.content().length() > contentThreshold) {
                String reference = "history://tool-result/" + message.toolCallId();
                projected.add(new RuntimeMessage("tool",
                        "{\"archived\":true,\"ref\":\"" + reference + "\"}",
                        message.toolCallId(), List.of()));
                references.add(new ArchiveReference(reference, message.toolCallId(), message.content()));
            } else {
                projected.add(message);
            }
        }
        return new Result(List.copyOf(projected), List.copyOf(references));
    }

    public record ArchiveReference(String reference, String toolCallId, String originalContent) {}
    public record Result(List<RuntimeMessage> messages, List<ArchiveReference> references) {}
}
