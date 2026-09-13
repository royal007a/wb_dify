package com.hify.provider.api;

public record ProviderRuntimeConfig(
        String id,
        String name,
        ProviderType type,
        String baseUrl,
        String authConfig,
        String defaultModelId,
        boolean enabled
) {}
