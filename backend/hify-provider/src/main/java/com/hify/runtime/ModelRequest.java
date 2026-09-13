package com.hify.runtime;

import java.util.List;

public record ModelRequest(
        String model,
        double temperature,
        List<RuntimeMessage> messages,
        List<ToolDefinition> tools
) {}

