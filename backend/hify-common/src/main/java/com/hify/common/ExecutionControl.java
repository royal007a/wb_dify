package com.hify.common;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class ExecutionControl {
    private static final ExecutionControl NONE = new ExecutionControl(Long.MAX_VALUE, () -> false);

    private final long deadlineNanos;
    private final BooleanSupplier cancelled;
    private final BooleanSupplier stopping;

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled) {
        this(deadlineNanos, cancelled, () -> false);
    }

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled, BooleanSupplier stopping) {
        this.deadlineNanos = deadlineNanos;
        this.cancelled = cancelled;
        this.stopping = stopping;
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

    /** A child can tighten a deadline, never restart or extend its parent's budget. */
    public ExecutionControl boundedBy(Duration timeout) {
        long childDeadline = withTimeout(timeout, cancelled).deadlineNanos;
        return new ExecutionControl(Math.min(deadlineNanos, childDeadline), cancelled, stopping);
    }

    /** Control exits are not dependency failures; explicit cancellation wins over time. */
    public void checkActive() {
        throwIfCancelled();
        if (isExpired()) throw new ExecutionTimedOutException();
    }

    public boolean isCancelled() {
        return cancelled.getAsBoolean() || (!stopping.getAsBoolean() && Thread.currentThread().isInterrupted());
    }

    public ExecutionControl withShutdown(BooleanSupplier stopping) {
        return new ExecutionControl(deadlineNanos, cancelled, () -> this.stopping.getAsBoolean() || stopping.getAsBoolean());
    }

    public ExecutionControl withCancellation(BooleanSupplier cancelled) {
        return new ExecutionControl(deadlineNanos,
                () -> this.cancelled.getAsBoolean() || (cancelled != null && cancelled.getAsBoolean()), stopping);
    }

    public boolean isSuspended() { return stopping.getAsBoolean() && !cancelled.getAsBoolean(); }

    public void throwIfSuspended() { if (isSuspended()) throw new ExecutionSuspendedException(); }

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
        throwIfSuspended();
        if (isCancelled()) throw new ExecutionCancelledException("Execution cancelled");
    }
}
