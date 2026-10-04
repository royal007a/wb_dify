package com.hify.provider.api;

/** Immutable embedding space. authConfig contains references only, never credential values. */
public record EmbeddingProfile(String providerId, ProviderType providerType, String baseUrl,
                               String authConfig, String model, int dimensions) {}
