package com.hify.knowledge.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RetrievalTestRequest(@NotBlank String query, @Min(1) @Max(20) Integer topK) {}
