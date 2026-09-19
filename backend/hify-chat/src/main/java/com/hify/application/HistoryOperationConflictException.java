package com.hify.application;

public class HistoryOperationConflictException extends RuntimeException {
    public HistoryOperationConflictException(String runId, String operationId) {
        super("History operation identity was reused with different content: " + runId + "/" + operationId);
    }
}
