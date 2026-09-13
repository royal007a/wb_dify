package com.hify.common;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class ExecutionControl {
    private static final ExecutionControl NONE = new ExecutionControl(Long.MAX_VALUE, () -> false);

    private final long deadlineNanos;
    private final BooleanSupplier cancelled;

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled) {
        this.deadlineNanos = deadlineNanos;
        this.cancelled = cancelled;
    }

    public static ExecutionControl withTimeout(Duration timeout, BooleanSupplier cancelled) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        long deadline = nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
        return new ExecutionControl(deadline, cancelled == null ? () -> false : cancelled);
    }

    public static ExecutionControl none() {
        return NONE;
    }

    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    public boolean isExpired() {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    public Duration remaining(Duration cap) {
        if (deadlineNanos == Long.MAX_VALUE) return cap;
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        Duration value = Duration.ofNanos(remaining);
        return value.compareTo(cap) < 0 ? value : cap;
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new ExecutionCancelledException("Execution cancelled");
    }
}
