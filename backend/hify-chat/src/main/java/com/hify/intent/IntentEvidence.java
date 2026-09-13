package com.hify.intent;

public record IntentEvidence(String source, String reference, String detail) {
    public IntentEvidence {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Evidence source is required");
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Evidence reference is required");
        detail = detail == null ? "" : detail;
    }
}
