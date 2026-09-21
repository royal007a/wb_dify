package com.hify.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkflowNodeSpec(@NotBlank String nodeKey,@NotBlank String type,@NotBlank String name,@NotNull JsonNode config) {}
