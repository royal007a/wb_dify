package com.hify.knowledge.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseRequest(
        @NotBlank @Size(max=160) String name,
        @Size(max=1000) String description,
        @Min(64) @Max(2048) Integer chunkSize,
        @Min(0) Integer chunkOverlap,
        Boolean enabled) {}
