package com.hify.agent.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AgentUpsertRequest(
        @NotBlank String name,
        String description,
        @NotBlank String instructions,
        @NotBlank String providerId,
        String modelId,
        @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
        @Min(1) @Max(32768) int maxTokens,
        @Min(1) @Max(20) int maxTurns,
        @Min(1) @Max(100) int maxContextTurns,
        List<String> enabledTools,
        Boolean enabled
) {
    public AgentUpsertRequest {
        enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }
}
