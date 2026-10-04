package com.hify.runtime;

import com.hify.common.ExecutionControl;
import java.util.List;

public record ModelRequest(
        String model,
        double temperature,
        List<RuntimeMessage> messages,
        List<ToolDefinition> tools,
        ExecutionControl control,
        Integer maxOutputTokens
) {
    public ModelRequest(String model, double temperature, List<RuntimeMessage> messages,
                        List<ToolDefinition> tools, ExecutionControl control) {
        this(model, temperature, messages, tools, control, null);
    }

    public ModelRequest(String model, double temperature, List<RuntimeMessage> messages,
                        List<ToolDefinition> tools) {
        this(model, temperature, messages, tools, ExecutionControl.none());
    }

    public ModelRequest {
        if (maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 4096))
            throw new IllegalArgumentException("Output token budget must be 1..4096");
        control = control == null ? ExecutionControl.none() : control;
    }
}
