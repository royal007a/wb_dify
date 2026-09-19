package com.hify.runtime;

public class StaleToolExecutionException extends RuntimeException {
    public StaleToolExecutionException(String message) {
        super(message);
    }
}
