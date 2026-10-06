package com.hify.common;

/** The owning execution's deadline elapsed, independently of a dependency's timeout. */
public final class ExecutionTimedOutException extends RuntimeException {
    public ExecutionTimedOutException() {
        super("Execution deadline expired");
    }
}
