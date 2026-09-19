package com.hify.domain;

public enum ChildTaskState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, LOST;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == LOST;
    }
}
