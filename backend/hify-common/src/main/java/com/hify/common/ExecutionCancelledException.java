package com.hify.common;

public class ExecutionCancelledException extends RuntimeException {
    public ExecutionCancelledException(String message) {
        super(message);
    }
}
