package com.hify.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AgentToolBindingRequest(
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 255) String> toolIds
) {
    public AgentToolBindingRequest {
        toolIds = toolIds == null ? null : List.copyOf(toolIds);
    }
}
