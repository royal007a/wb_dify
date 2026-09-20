package com.hify.runtime;

/** Allows a tool result to state whether it is navigation or canonical evidence. */
public interface ToolEvidencePayload {
    EvidenceKind evidenceKind();
    String sourceRef();
    String valueDigest();
    String evidenceSummary();
    int estimatedTokens();

    enum EvidenceKind { NAVIGATION, CANONICAL }
}
