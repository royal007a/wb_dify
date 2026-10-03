package com.hify.workflow.application;

import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import java.time.Duration;
import java.util.concurrent.*;

final class WorkflowControl {
    static void check(ExecutionControl control) {
        control.throwIfCancelled();
        if (control.isExpired()) throw new DeadlineExceeded();
    }

    static <T> T call(ExecutionControl control, Executor executor, Callable<T> action) {
        check(control);
        FutureTask<T> task = new FutureTask<>(() -> { check(control); T result = action.call(); check(control); return result; });
        try {
            executor.execute(task);
            while (true) {
                check(control);
                try {
                    T result = task.get(Math.max(1, control.remaining(Duration.ofMillis(50)).toNanos()), TimeUnit.NANOSECONDS);
                    check(control);
                    return result;
                } catch (TimeoutException waiting) {
                    // One original deadline, including time spent queued; no per-poll reset.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            control.throwIfSuspended();
            throw new ExecutionCancelledException("Workflow execution interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Workflow retrieval failed", failure.getCause());
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
    }

    static final class DeadlineExceeded extends RuntimeException {
        DeadlineExceeded() { super("Workflow deadline exceeded"); }
    }
}
