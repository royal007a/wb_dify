package com.hify.provider.api;

import java.time.LocalDateTime;

public record ConnectionTestResponse(
        boolean success,
        long latencyMs,
        String providerCode,
        String message,
        LocalDateTime checkedAt
) {}
