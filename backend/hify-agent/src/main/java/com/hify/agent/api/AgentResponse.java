package com.hify.agent.api;

import java.time.Instant;
import java.util.List;

public record AgentResponse(
        String id, String name, String description, String instructions,
        String providerId, String modelId, double temperature, int maxTokens,
        int maxTurns, int maxContextTurns, List<String> enabledTools, boolean enabled,
        int draftRevision, String publishedVersionId, Integer publishedVersionNo,
        boolean hasUnpublishedChanges,
        Instant createdAt, Instant updatedAt
) {}
