package com.hify.workflow.api;
import jakarta.validation.constraints.NotBlank;
public record WorkflowRunRequest(@NotBlank String input) {}
