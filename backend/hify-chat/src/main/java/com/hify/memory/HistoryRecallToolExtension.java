package com.hify.memory;

import com.hify.common.ExecutionControl;
import com.hify.domain.DetailRefKind;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.RuntimeToolExtension;
import com.hify.runtime.ToolDefinition;
import com.hify.runtime.ToolExecutionLease;
import com.hify.runtime.ToolRuntime;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class HistoryRecallToolExtension implements RuntimeToolExtension {
    private final HistoryRecallService recall;

    public HistoryRecallToolExtension(HistoryRecallService recall) { this.recall = recall; }

    @Override
    public List<ToolDefinition> definitions() {
        return List.of(
                new ToolDefinition("history.search",
                        "Search this conversation's canonical history catalog. Results navigate to refs; they are not evidence.",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "kind", Map.of("type", "string"),
                                "entity", Map.of("type", "string"),
                                "from", Map.of("type", "string"),
                                "to", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")
                        ), "required", List.of("query")), "read"),
                new ToolDefinition("history.detail",
                        "Load and digest-verify one canonical history detail ref. This can become VERIFIED evidence.",
                        Map.of("type", "object", "properties", Map.of(
                                "ref", Map.of("type", "string")
                        ), "required", List.of("ref")), "read")
        );
    }

    @Override
    public ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call, ToolExecutionLease lease,
                                               ExecutionControl control) {
        control.throwIfCancelled();
        return switch (call.name()) {
            case "history.search" -> ToolRuntime.ExecutionResult.success(recall.search(lease.runId(),
                    new HistoryRecallService.SearchQuery(text(call, "query"), kind(call.arguments().get("kind")),
                            instant(call.arguments().get("from")), instant(call.arguments().get("to")),
                            optional(call.arguments().get("entity")), integer(call.arguments().get("limit"), 8))));
            case "history.detail" -> ToolRuntime.ExecutionResult.success(
                    recall.detail(lease.runId(), text(call, "ref")));
            default -> ToolRuntime.ExecutionResult.executionFailed("Unsupported history tool", true);
        };
    }

    private String text(RuntimeMessage.ToolCall call, String key) {
        Object value = call.arguments().get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return text;
    }
    private String optional(Object value) { return value == null ? null : String.valueOf(value); }
    private DetailRefKind kind(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null
                : DetailRefKind.valueOf(String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
    }
    private Instant instant(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : Instant.parse(String.valueOf(value));
    }
    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
