package com.hify.common;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ExecutionControl {
    private static final long UNLIMITED = -1;
    private static final ExecutionControl NONE = new ExecutionControl(0, UNLIMITED,
            () -> false, () -> false, System::nanoTime);

    private final long startedNanos;
    private final long timeoutNanos;
    private final BooleanSupplier cancelled;
    private final BooleanSupplier stopping;
    private final LongSupplier nanoTime;

    private ExecutionControl(long startedNanos, long timeoutNanos, BooleanSupplier cancelled,
                             BooleanSupplier stopping, LongSupplier nanoTime) {
        this.startedNanos = startedNanos;
        this.timeoutNanos = timeoutNanos;
        this.cancelled = cancelled;
        this.stopping = stopping;
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    public static ExecutionControl withTimeout(Duration timeout, BooleanSupplier cancelled) {
        return withTimeout(timeout, cancelled, System::nanoTime);
    }

    // Package-private dependency seam; production callers cannot configure or replace the clock.
    static ExecutionControl withTimeout(Duration timeout, BooleanSupplier cancelled, LongSupplier nanoTime) {
        long nanos = positiveNanos(timeout);
        return new ExecutionControl(nanoTime.getAsLong(), nanos,
                cancelled == null ? () -> false : cancelled, () -> false, nanoTime);
    }

    private static long positiveNanos(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        return timeout.toNanos();
    }

    public static ExecutionControl none() {
        return NONE;
    }

    /** A child can tighten a deadline, never restart or extend its parent's budget. */
    public ExecutionControl boundedBy(Duration timeout) {
        long requestedNanos = positiveNanos(timeout);
        long now = nanoTime.getAsLong();
        long childNanos = timeoutNanos == UNLIMITED ? requestedNanos : Math.min(requestedNanos, remainingNanosAt(now));
        return new ExecutionControl(now, childNanos, cancelled, stopping, nanoTime);
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
        return new ExecutionControl(startedNanos, timeoutNanos, cancelled,
                () -> this.stopping.getAsBoolean() || stopping.getAsBoolean(), nanoTime);
    }

    public ExecutionControl withCancellation(BooleanSupplier cancelled) {
        return new ExecutionControl(startedNanos, timeoutNanos,
                () -> this.cancelled.getAsBoolean() || (cancelled != null && cancelled.getAsBoolean()), stopping, nanoTime);
    }

    public boolean isSuspended() { return stopping.getAsBoolean() && !cancelled.getAsBoolean(); }

    public void throwIfSuspended() { if (isSuspended()) throw new ExecutionSuspendedException(); }

    public boolean isExpired() {
        return timeoutNanos != UNLIMITED && remainingNanosAt(nanoTime.getAsLong()) == 0;
    }

    public Duration remaining(Duration cap) {
        if (timeoutNanos == UNLIMITED) return cap;
        Duration value = Duration.ofNanos(remainingNanosAt(nanoTime.getAsLong()));
        return value.compareTo(cap) < 0 ? value : cap;
    }

    private long remainingNanosAt(long now) {
        // nanoTime has an arbitrary, possibly negative origin. Subtraction remains correct
        // across signed wraparound for elapsed intervals < 2^63 ns (the JDK contract).
        long elapsed = now - startedNanos;
        // An invalid/backward clock or an interval outside that range fails closed.
        return elapsed < 0 || elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }

    public void throwIfCancelled() {
        throwIfSuspended();
        if (isCancelled()) throw new ExecutionCancelledException("Execution cancelled");
    }
}
