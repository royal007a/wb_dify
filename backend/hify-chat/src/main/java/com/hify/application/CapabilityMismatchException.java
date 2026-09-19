package com.hify.application;

public class CapabilityMismatchException extends RuntimeException {
    public CapabilityMismatchException(String runId) {
        super("Pinned capability snapshot no longer matches runtime for Run " + runId);
    }
}
