package com.hify.provider.api;

public record ProviderModelResponse(
        Long id,
        String displayName,
        String modelId,
        boolean enabled,
        boolean isDefault
) {}
