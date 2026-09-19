package com.hify.runtime.context;

public class ContextWindowExceededException extends RuntimeException {
    public ContextWindowExceededException(String message) {
        super(message);
    }
}
