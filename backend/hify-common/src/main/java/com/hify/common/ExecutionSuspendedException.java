package com.hify.common;

/** The hosting application is stopping; preserve durable work for startup recovery. */
public final class ExecutionSuspendedException extends RuntimeException {
    public ExecutionSuspendedException() { super("Execution interrupted by application shutdown"); }
}
