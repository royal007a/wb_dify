package com.hify.common;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

@Component
public class CircuitBreakerService {
    private final CircuitBreakerRegistry circuitBreakers;
    private final Executor executor;
    private final Duration overallTimeout;
    private final RetryConfig timeoutRetry;
    private final RetryConfig rateLimitRetry;

    @Autowired
    public CircuitBreakerService(
            CircuitBreakerRegistry circuitBreakers,
            @Qualifier("asyncExecutor") Executor executor,
            @Value("${hify.model-timeout:45s}") Duration overallTimeout,
            @Value("${hify.resilience.timeout-max-attempts:3}") int timeoutMaxAttempts,
            @Value("${hify.resilience.timeout-wait:1s}") Duration timeoutWait,
            @Value("${hify.resilience.rate-limit-max-attempts:3}") int rateLimitMaxAttempts,
            @Value("${hify.resilience.rate-limit-wait:2s}") Duration rateLimitWait) {
        this.circuitBreakers = circuitBreakers;
        this.executor = executor;
        this.overallTimeout = overallTimeout;
        this.timeoutRetry = RetryConfig.custom()
                .maxAttempts(timeoutMaxAttempts)
                .waitDuration(timeoutWait)
                .retryOnException(CircuitBreakerService::retryTimeoutOrUnavailable)
                .build();
        this.rateLimitRetry = RetryConfig.custom()
                .maxAttempts(rateLimitMaxAttempts)
                .intervalFunction(attempt -> rateLimitWait.toMillis() * (1L << Math.max(0, attempt - 1)))
                .retryOnException(CircuitBreakerService::retryRateLimit)
                .build();
    }

    CircuitBreakerService(CircuitBreakerRegistry circuitBreakers, int timeoutMaxAttempts,
                          Duration timeoutWait, int rateLimitMaxAttempts, Duration rateLimitWait) {
        this(circuitBreakers, Runnable::run, Duration.ofSeconds(30), timeoutMaxAttempts,
                timeoutWait, rateLimitMaxAttempts, rateLimitWait);
    }

    /**
     * Protects a pre-stream request. Do not wrap an operation after response bytes have been emitted.
     */
    public <T> T execute(String providerName, Supplier<T> operation) {
        return execute(providerName, ExecutionControl.none(), operation);
    }

    public <T> T execute(String providerName, ExecutionControl control, Supplier<T> operation) {
        long localDeadline = System.nanoTime() + overallTimeout.toNanos();
        checkActive(control, localDeadline);
        CallProbe probe = new CallProbe(circuitBreakers.circuitBreaker(normalize(providerName)));
        FutureTask<T> future = new FutureTask<>(() -> executeProtected(providerName, control, localDeadline, probe, () -> {
            // Recheck for every retry as well as for queued work; never reset the original deadline.
            checkActive(control, localDeadline);
            probe.beforeAttempt();
            T result = operation.get();
            checkActive(control, localDeadline);
            return result;
        }));
        try {
            executor.execute(future);
            while (true) {
                checkActive(control, localDeadline);
                long waitNanos = Math.max(1, control.remaining(Duration.ofMillis(100)).toNanos());
                try {
                    T result = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    checkActive(control, localDeadline);
                    return result;
                } catch (TimeoutException ignored) {
                    // Poll cancellation/deadline between retry attempts.
                }
            }
        } catch (RejectedExecutionException exception) {
            throw new ExecutionRejectedException();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException("LLM provider execution interrupted");
        } catch (ExecutionException exception) {
            checkActive(control, localDeadline);
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED,
                    "LLM provider execution failed", exception.getCause());
        } finally {
            // Settle before interrupting: HTTP cancellation may otherwise hide an upstream SLA
            // timeout. A driver that ignores interruption must not keep a HALF_OPEN permit.
            if (control.isCancelled() || control.isExpired()) probe.ignore();
            else if (System.nanoTime() >= localDeadline) probe.modelTimeout();
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static void checkActive(ExecutionControl control, long localDeadline) {
        control.throwIfCancelled();
        if (control.isExpired() || System.nanoTime() >= localDeadline) {
            throw new LlmApiException(LlmApiException.Type.TIMEOUT,
                    "LLM provider attempts exceeded remaining run deadline");
        }
    }

    private <T> T executeProtected(String providerName, ExecutionControl control, long localDeadline,
                                   CallProbe probe, Supplier<T> operation) {
        String name = normalize(providerName);
        RetryConfig activeTimeoutRetry = RetryConfig.from(timeoutRetry).retryOnException(failure ->
                eligible(control, localDeadline) && retryTimeoutOrUnavailable(failure)).build();
        RetryConfig activeRateRetry = RetryConfig.from(rateLimitRetry).retryOnException(failure ->
                eligible(control, localDeadline) && retryRateLimit(failure)).build();
        Supplier<T> timeouts = Retry.decorateSupplier(Retry.of(name + "-timeout", activeTimeoutRetry), operation);
        Supplier<T> rateLimits = Retry.decorateSupplier(Retry.of(name + "-rate-limit", activeRateRetry), timeouts);
        // Queued work can expire before the worker starts: it never sampled this provider.
        checkActive(control, localDeadline);
        probe.acquire();
        T result;
        try {
            result = rateLimits.get();
        } catch (RuntimeException failure) {
            // Per-call control is essential: a cancelled HTTP call may surface as an ordinary
            // timeout/IO error. A shared ignoreExceptions predicate cannot inspect this control.
            if (control.isCancelled() || control.isExpired() || isLocalFailure(failure)) probe.ignore();
            else probe.failure(failure);
            if (failure instanceof RejectedExecutionException) throw new ExecutionRejectedException();
            throw failure;
        } catch (Error failure) {
            probe.ignore();
            throw failure;
        }
        if (control.isCancelled() || control.isExpired()) probe.ignore();
        else probe.success(result);
        return result;
    }

    /** Only bookkeeping is synchronized; never execute user code or blocking IO under this lock. */
    private static final class CallProbe {
        private final CircuitBreaker breaker;
        private boolean acquired, attempted, settled;
        private long started;

        CallProbe(CircuitBreaker breaker) { this.breaker = breaker; }

        synchronized void acquire() {
            requireActive();
            breaker.acquirePermission();
            acquired = true;
            started = breaker.getCurrentTimestamp();
        }

        synchronized void beforeAttempt() {
            requireActive();
            attempted = true;
        }

        private void requireActive() {
            if (settled) throw new ExecutionCancelledException("LLM provider attempt already stopped");
        }

        synchronized void ignore() {
            if (settled) return;
            settled = true;
            if (acquired) breaker.releasePermission();
        }

        synchronized void modelTimeout() {
            failure(new LlmApiException(LlmApiException.Type.TIMEOUT, "LLM provider attempt exceeded model deadline"));
        }

        synchronized void failure(Throwable failure) {
            if (settled) return;
            settled = true;
            if (!acquired) return;
            if (!attempted) breaker.releasePermission();
            else breaker.onError(breaker.getCurrentTimestamp() - started, breaker.getTimestampUnit(), failure);
        }

        synchronized void success(Object result) {
            if (settled) return;
            settled = true;
            breaker.onResult(breaker.getCurrentTimestamp() - started, breaker.getTimestampUnit(), result);
        }
    }

    private static boolean isLocalFailure(Throwable failure) {
        // Do not ignore InterruptedIOException generally: SocketTimeoutException is its subtype
        // and is genuine upstream failure. Typed cancellation or per-call control is required.
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ExecutionCancelledException || cause instanceof ExecutionRejectedException
                    || cause instanceof RejectedExecutionException || cause instanceof InterruptedException) return true;
        }
        return false;
    }

    private static boolean eligible(ExecutionControl control, long deadline) {
        return !control.isCancelled() && !control.isExpired() && System.nanoTime() < deadline;
    }

    private static boolean retryTimeoutOrUnavailable(Throwable failure) {
        return !isLocalFailure(failure) && failure instanceof LlmApiException exception
                && (exception.type() == LlmApiException.Type.TIMEOUT
                || exception.type() == LlmApiException.Type.PROVIDER_UNAVAILABLE);
    }

    private static boolean retryRateLimit(Throwable failure) {
        return !isLocalFailure(failure) && failure instanceof LlmApiException exception
                && exception.type() == LlmApiException.Type.RATE_LIMITED;
    }

    private static String normalize(String providerName) {
        String safe = providerName == null ? "unknown" : providerName.replaceAll("[^A-Za-z0-9._-]", "_");
        return "provider-" + safe;
    }
}
