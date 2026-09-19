package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolDefinition;
import java.util.List;

/** Conservative, deterministic estimator used for admission control; providers may replace it later. */
public final class ContextTokenEstimator {
    public int estimate(List<RuntimeMessage> messages, List<ToolDefinition> tools) {
        int characters = 0;
        for (RuntimeMessage message : messages) {
            characters += length(message.role()) + length(message.content()) + length(message.toolCallId());
            if (message.toolCalls() != null) {
                for (RuntimeMessage.ToolCall call : message.toolCalls()) {
                    characters += length(call.id()) + length(call.name()) + String.valueOf(call.arguments()).length();
                }
            }
        }
        for (ToolDefinition tool : tools) {
            characters += length(tool.name()) + length(tool.description())
                    + String.valueOf(tool.inputSchema()).length() + length(tool.risk());
        }
        return Math.max(1, (characters + 3) / 4);
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }
}
