package com.hify.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentWorkflowBindingRequest(@NotBlank @Size(max = 64) String workflowId) {}
