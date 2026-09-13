package com.hify.runtime;

@FunctionalInterface
public interface ModelStreamObserver {
    ModelStreamObserver NOOP = delta -> {};
    void onTextDelta(String delta);
}
