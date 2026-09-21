package com.hify.workflow.api;

import jakarta.validation.constraints.NotBlank;

public record WorkflowEdgeSpec(@NotBlank String edgeKey,@NotBlank String sourceNodeKey,@NotBlank String targetNodeKey,
                               String condition,boolean defaultBranch) {}
