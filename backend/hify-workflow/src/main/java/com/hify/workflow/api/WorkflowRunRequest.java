package com.hify.workflow.api;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;
public record WorkflowRunRequest(@NotBlank @Size(max=20000) String input,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=WorkflowJson.DecimalTreeDeserializer.class) JsonNode inputs) {}
