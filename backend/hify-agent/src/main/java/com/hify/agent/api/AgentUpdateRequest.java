package com.hify.agent.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 8000) String instructions,
        @NotBlank @Size(max = 64) String providerId,
        @NotBlank @Size(max = 255) String modelId,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double temperature,
        @NotNull @Min(1) @Max(32768) Integer maxTokens,
        @NotNull @Min(1) @Max(20) Integer maxTurns,
        @NotNull @Min(1) @Max(100) Integer maxContextTurns,
        Boolean enabled
) {
    public AgentUpdateRequest {
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }
}
