package com.hify.domain;

public enum RunState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    LIMIT_EXCEEDED,
    NEEDS_INPUT;

    public boolean terminal() {
        return this != RUNNING;
    }
}
