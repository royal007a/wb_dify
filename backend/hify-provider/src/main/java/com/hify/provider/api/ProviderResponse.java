package com.hify.provider.api;

import java.time.LocalDateTime;
import java.util.List;

public record ProviderResponse(
        String id,
        String name,
        ProviderType type,
        String baseUrl,
        boolean enabled,
        boolean credentialConfigured,
        String defaultModelId,
        List<ProviderModelResponse> models,
        ProviderHealthResponse health,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
