package com.hify.demo.api;

import java.time.LocalDateTime;

public record DemoItemResponse(
        Long id,
        String name,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

