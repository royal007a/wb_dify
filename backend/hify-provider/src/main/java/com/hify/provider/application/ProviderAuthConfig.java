package com.hify.provider.application;

public record ProviderAuthConfig(
        int version,
        String credentialRef,
        String headerName,
        String prefix
) {}
