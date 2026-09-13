package com.hify.domain;

public enum RunState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    LIMIT_EXCEEDED;

    public boolean terminal() {
        return this != RUNNING;
    }
}
