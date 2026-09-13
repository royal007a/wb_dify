package com.hify.common;

public class LlmApiException extends RuntimeException {
    public enum Type {
        TIMEOUT,
        AUTH_FAILED,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        REQUEST_FAILED
    }

    private final Type type;

    public LlmApiException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public LlmApiException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public Type type() { return type; }
}

