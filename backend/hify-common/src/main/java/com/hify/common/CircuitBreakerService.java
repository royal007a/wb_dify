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
        FutureTask<T> future = new FutureTask<>(() -> executeProtected(providerName, control, localDeadline, () -> {
            // Recheck for every retry as well as for queued work; never reset the original deadline.
            checkActive(control, localDeadline);
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
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException("LLM provider execution interrupted");
        } catch (ExecutionException exception) {
            checkActive(control, localDeadline);
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED,
                    "LLM provider execution failed", exception.getCause());
        } finally {
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

    private <T> T executeProtected(String providerName, ExecutionControl control, long localDeadline, Supplier<T> operation) {
        String name = normalize(providerName);
        RetryConfig activeTimeoutRetry = RetryConfig.from(timeoutRetry).retryOnException(failure ->
                eligible(control, localDeadline) && retryTimeoutOrUnavailable(failure)).build();
        RetryConfig activeRateRetry = RetryConfig.from(rateLimitRetry).retryOnException(failure ->
                eligible(control, localDeadline) && retryRateLimit(failure)).build();
        Supplier<T> timeouts = Retry.decorateSupplier(Retry.of(name + "-timeout", activeTimeoutRetry), operation);
        Supplier<T> rateLimits = Retry.decorateSupplier(Retry.of(name + "-rate-limit", activeRateRetry), timeouts);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(name);
        // Queued work can expire before the worker starts: it never sampled this provider.
        checkActive(control, localDeadline);
        breaker.acquirePermission();
        long started = breaker.getCurrentTimestamp();
        T result;
        try {
            result = rateLimits.get();
        } catch (RuntimeException failure) {
            // Per-call control is essential: a cancelled HTTP call may surface as an ordinary
            // timeout/IO error. A shared ignoreExceptions predicate cannot inspect this control.
            if (control.isCancelled() || control.isExpired()
                    || failure instanceof ExecutionCancelledException
                    || failure instanceof ExecutionRejectedException
                    || failure instanceof RejectedExecutionException) {
                breaker.releasePermission(); // also restores a HALF_OPEN probe, without success credit
            } else {
                breaker.onError(breaker.getCurrentTimestamp() - started, breaker.getTimestampUnit(), failure);
            }
            if (failure instanceof RejectedExecutionException) throw new ExecutionRejectedException();
            throw failure;
        } catch (Error failure) {
            breaker.releasePermission();
            throw failure;
        }
        breaker.onResult(breaker.getCurrentTimestamp() - started, breaker.getTimestampUnit(), result);
        return result;
    }

    private static boolean eligible(ExecutionControl control, long deadline) {
        return !control.isCancelled() && !control.isExpired() && System.nanoTime() < deadline;
    }

    private static boolean retryTimeoutOrUnavailable(Throwable failure) {
        return failure instanceof LlmApiException exception
                && (exception.type() == LlmApiException.Type.TIMEOUT
                || exception.type() == LlmApiException.Type.PROVIDER_UNAVAILABLE);
    }

    private static boolean retryRateLimit(Throwable failure) {
        return failure instanceof LlmApiException exception
                && exception.type() == LlmApiException.Type.RATE_LIMITED;
    }

    private static String normalize(String providerName) {
        String safe = providerName == null ? "unknown" : providerName.replaceAll("[^A-Za-z0-9._-]", "_");
        return "provider-" + safe;
    }
}
