package com.hify.mcp.api;
import com.fasterxml.jackson.databind.JsonNode; import jakarta.validation.constraints.NotNull;
public record McpDebugRequest(@NotNull JsonNode arguments){}
