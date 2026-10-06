package com.hify.common;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ExecutionControl {
    private static final ExecutionControl NONE = new ExecutionControl(Long.MAX_VALUE, () -> false);

    private final long deadlineNanos;
    private final BooleanSupplier cancelled;
    private final BooleanSupplier stopping;
    private final LongSupplier nanoTime;

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled) {
        this(deadlineNanos, cancelled, () -> false);
    }

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled, BooleanSupplier stopping) {
        this(deadlineNanos, cancelled, stopping, System::nanoTime);
    }

    private ExecutionControl(long deadlineNanos, BooleanSupplier cancelled, BooleanSupplier stopping, LongSupplier nanoTime) {
        this.deadlineNanos = deadlineNanos;
        this.cancelled = cancelled;
        this.stopping = stopping;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    public static ExecutionControl withTimeout(Duration timeout, BooleanSupplier cancelled) {
        return withTimeout(timeout, cancelled, System::nanoTime);
    }

    // Package-private dependency seam; production callers cannot configure or replace the clock.
    static ExecutionControl withTimeout(Duration timeout, BooleanSupplier cancelled, LongSupplier nanoTime) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        long now = nanoTime.getAsLong();
        long nanos = timeout.toNanos();
        long deadline = nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
        return new ExecutionControl(deadline, cancelled == null ? () -> false : cancelled, () -> false, nanoTime);
    }

    public static ExecutionControl none() {
        return NONE;
    }

    /** A child can tighten a deadline, never restart or extend its parent's budget. */
    public ExecutionControl boundedBy(Duration timeout) {
        long childDeadline = withTimeout(timeout, cancelled, nanoTime).deadlineNanos;
        return new ExecutionControl(Math.min(deadlineNanos, childDeadline), cancelled, stopping, nanoTime);
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
        return new ExecutionControl(deadlineNanos, cancelled, () -> this.stopping.getAsBoolean() || stopping.getAsBoolean(), nanoTime);
    }

    public ExecutionControl withCancellation(BooleanSupplier cancelled) {
        return new ExecutionControl(deadlineNanos,
                () -> this.cancelled.getAsBoolean() || (cancelled != null && cancelled.getAsBoolean()), stopping, nanoTime);
    }

    public boolean isSuspended() { return stopping.getAsBoolean() && !cancelled.getAsBoolean(); }

    public void throwIfSuspended() { if (isSuspended()) throw new ExecutionSuspendedException(); }

    public boolean isExpired() {
        return deadlineNanos != Long.MAX_VALUE && nanoTime.getAsLong() >= deadlineNanos;
    }

    public Duration remaining(Duration cap) {
        if (deadlineNanos == Long.MAX_VALUE) return cap;
        long remaining = Math.max(0, deadlineNanos - nanoTime.getAsLong());
        Duration value = Duration.ofNanos(remaining);
        return value.compareTo(cap) < 0 ? value : cap;
    }

    public void throwIfCancelled() {
        throwIfSuspended();
        if (isCancelled()) throw new ExecutionCancelledException("Execution cancelled");
    }
}
