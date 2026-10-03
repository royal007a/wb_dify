package com.hify.runtime;

/** Canonical data exists but cannot safely be used as the next operation. Never regenerate it. */
public class HistoryReplayException extends RuntimeException {
    public HistoryReplayException(String message){super(message);}
}
