package com.hify.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRuntimeTest {
    private final ToolRuntime tools = new ToolRuntime();

    @Test
    void validatesRequiredCalculatorInput() {
        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "calculator", Map.of()),
                Set.of("calculator"));

        assertThat(result.error()).isTrue();
        assertThat(result.fatal()).isFalse();
        assertThat(result.value()).isEqualTo("Missing required argument: expression");
    }

    @Test
    void deniesToolsOutsideAgentAllowList() {
        ToolRuntime.ExecutionResult result = tools.execute(
                new RuntimeMessage.ToolCall("call-1", "current_time", Map.of()),
                Set.of());

        assertThat(result.permissionDenied()).isTrue();
        assertThat(result.fatal()).isTrue();
    }
}
