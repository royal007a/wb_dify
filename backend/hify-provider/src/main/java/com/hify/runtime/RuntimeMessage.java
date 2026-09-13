package com.hify.runtime;

import java.util.List;
import java.util.Map;

public record RuntimeMessage(
        String role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {
    public static RuntimeMessage system(String content) {
        return new RuntimeMessage("system", content, null, List.of());
    }

    public static RuntimeMessage user(String content) {
        return new RuntimeMessage("user", content, null, List.of());
    }

    public static RuntimeMessage assistant(String content) {
        return new RuntimeMessage("assistant", content, null, List.of());
    }

    public static RuntimeMessage toolCalls(List<ToolCall> calls) {
        return new RuntimeMessage("assistant", null, null, calls);
    }

    public static RuntimeMessage toolResult(String callId, Object value, boolean error) {
        return new RuntimeMessage("tool", ToolResult.serialize(value, error), callId, List.of());
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    private static final class ToolResult {
        private static String serialize(Object value, boolean error) {
            return "{\"ok\":" + !error + ",\"result\":\"" +
                    String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        }
    }
}

