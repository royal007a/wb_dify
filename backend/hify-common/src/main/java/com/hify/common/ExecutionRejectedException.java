package com.hify.common;

/** Local capacity rejection: not an upstream failure, and never automatically retried. */
public final class ExecutionRejectedException extends LlmApiException {
    public ExecutionRejectedException() {
        // Do not retain the JDK rejection message/cause, which includes executor internals.
        super(Type.REQUEST_FAILED, "LLM executor capacity exhausted");
    }
}
