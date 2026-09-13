package com.hify.runtime;

import com.hify.common.ExecutionControl;
import java.util.List;

public record ModelRequest(
        String model,
        double temperature,
        List<RuntimeMessage> messages,
        List<ToolDefinition> tools,
        ExecutionControl control
) {
    public ModelRequest(String model, double temperature, List<RuntimeMessage> messages,
                        List<ToolDefinition> tools) {
        this(model, temperature, messages, tools, ExecutionControl.none());
    }

    public ModelRequest {
        control = control == null ? ExecutionControl.none() : control;
    }
}
