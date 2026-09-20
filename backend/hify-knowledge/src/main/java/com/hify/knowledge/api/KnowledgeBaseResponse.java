package com.hify.knowledge.api;

import java.time.Instant;

public record KnowledgeBaseResponse(String id, String name, String description, int chunkSize,
                                    int chunkOverlap, boolean enabled, long documentCount,
                                    Instant createdAt, Instant updatedAt) {}
