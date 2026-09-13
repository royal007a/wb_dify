package com.hify.runtime.state;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GapState(
        String id,
        Kind kind,
        String description,
        boolean blocking,
        String resolutionKey,
        List<String> acquisitionOptions,
        Status status,
        String sourceAttemptId,
        Instant openedAt,
        Instant resolvedAt
) {
    public enum Kind {
        MISSING_INPUT,
        INVALID_INPUT,
        MISSING_CAPABILITY,
        MISSING_PERMISSION,
        UNVERIFIED_CLAIM,
        TRANSIENT_FAILURE,
        NO_PROGRESS
    }
    public enum Status { OPEN, RESOLVED, WAIVED }

    public GapState {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Gap id is required");
        if (kind == null) throw new IllegalArgumentException("Gap kind is required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Gap description is required");
        if (resolutionKey == null || resolutionKey.isBlank()) throw new IllegalArgumentException("Resolution key is required");
        acquisitionOptions = List.copyOf(acquisitionOptions == null ? List.of() : acquisitionOptions);
        if (status == null) throw new IllegalArgumentException("Gap status is required");
        if (openedAt == null) throw new IllegalArgumentException("Gap open time is required");
    }

    public static GapState open(Kind kind, String description, boolean blocking,
                                String resolutionKey, List<String> acquisitionOptions,
                                String sourceAttemptId) {
        return new GapState(UUID.randomUUID().toString(), kind, description, blocking,
                resolutionKey, acquisitionOptions, Status.OPEN, sourceAttemptId, Instant.now(), null);
    }

    public GapState resolve() {
        if (status != Status.OPEN) return this;
        return new GapState(id, kind, description, blocking, resolutionKey,
                acquisitionOptions, Status.RESOLVED, sourceAttemptId, openedAt, Instant.now());
    }
}
