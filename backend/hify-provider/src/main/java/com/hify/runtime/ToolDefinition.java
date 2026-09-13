package com.hify.runtime;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema,
        String risk
) {}

