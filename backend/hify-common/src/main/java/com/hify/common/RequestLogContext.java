package com.hify.common;

import org.slf4j.MDC;
import java.util.Map;
import java.util.Objects;

/** Diagnostic correlation only, never an authorization or tracing identity. */
public final class RequestLogContext {
    public static final String REQUEST_ID = "requestId";
    private RequestLogContext() {}

    public static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,64}");
    }

    public static Snapshot capture() {
        String value = MDC.get(REQUEST_ID);
        return new Snapshot(valid(value) ? value : null);
    }

    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task");
        Snapshot snapshot = capture();
        return () -> snapshot.run(task);
    }

    public static final class Snapshot {
        private final String requestId;
        private Snapshot(String requestId) { this.requestId = requestId; }

        public void run(Runnable task) {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                // Do not copy arbitrary parent MDC values or inherit a reused worker's identity.
                MDC.clear();
                if (requestId != null) MDC.put(REQUEST_ID, requestId);
                task.run();
            } finally {
                if (previous == null) MDC.clear();
                else MDC.setContextMap(previous);
            }
        }
    }
}
