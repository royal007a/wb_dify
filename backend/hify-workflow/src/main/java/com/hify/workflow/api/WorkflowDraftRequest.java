package com.hify.workflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkflowDraftRequest(@NotBlank @Size(max=160) String name,@Size(max=1000) String description,
                                   @Min(1) Integer schemaVersion,@NotEmpty List<@Valid WorkflowNodeSpec> nodes,
                                   List<@Valid WorkflowEdgeSpec> edges) {}
