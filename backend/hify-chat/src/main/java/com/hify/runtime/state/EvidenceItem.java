package com.hify.runtime.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record EvidenceItem(
        String id,
        String claimId,
        Type type,
        Status status,
        String sourceRef,
        String valueDigest,
        String summary,
        int planVersion,
        String attemptId,
        Instant observedAt
) {
    public enum Type { USER_INPUT, TOOL_RESULT, SYSTEM_RECORD, MODEL_OUTPUT, MODEL_INFERENCE }
    public enum Status { UNVERIFIED, VERIFIED, STALE, CONTRADICTED, INVALID }

    public EvidenceItem {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Evidence id is required");
        if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("Claim id is required");
        if (type == null || status == null) throw new IllegalArgumentException("Evidence type/status is required");
        if (sourceRef == null || sourceRef.isBlank()) throw new IllegalArgumentException("Evidence source is required");
        if (valueDigest == null || valueDigest.isBlank()) throw new IllegalArgumentException("Evidence digest is required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("Evidence summary is required");
        if (planVersion < 1) throw new IllegalArgumentException("Plan version must be positive");
        if (observedAt == null) throw new IllegalArgumentException("Evidence time is required");
    }

    public static EvidenceItem toolResult(String claimId, String toolCallId, String toolName,
                                          String attemptId, Object value, String failureType,
                                          int planVersion) {
        String canonical = toolName + "|" + failureType + "|" + String.valueOf(value);
        return new EvidenceItem(UUID.randomUUID().toString(), claimId, Type.TOOL_RESULT,
                Status.VERIFIED, toolCallId, sha256(canonical),
                "tool=" + toolName + ";failure=" + failureType,
                planVersion, attemptId, Instant.now());
    }

    public static EvidenceItem userInput(String claimId, String sourceRef, String value,
                                         int planVersion) {
        return new EvidenceItem(UUID.randomUUID().toString(), claimId, Type.USER_INPUT,
                Status.VERIFIED, sourceRef, sha256(value), "user supplied gap resolution",
                planVersion, null, Instant.now());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not digest evidence", exception);
        }
    }
}
