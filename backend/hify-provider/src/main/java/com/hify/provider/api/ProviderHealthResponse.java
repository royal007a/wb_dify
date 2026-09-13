package com.hify.provider.api;

import java.time.LocalDateTime;

public record ProviderHealthResponse(
        String status,
        Long latencyMs,
        String errorCode,
        String message,
        LocalDateTime checkedAt
) {}
