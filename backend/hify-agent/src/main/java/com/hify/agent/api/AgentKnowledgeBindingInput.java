package com.hify.agent.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AgentKnowledgeBindingInput(
        @NotBlank String knowledgeBaseId,
        @Min(1) @Max(20) int topK,
        @Min(0) int priority
) {}
