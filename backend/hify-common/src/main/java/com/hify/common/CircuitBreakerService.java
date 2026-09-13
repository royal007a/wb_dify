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
        FutureTask<T> future = new FutureTask<>(() -> executeProtected(providerName, operation));
        executor.execute(future);
        try {
            return future.get(overallTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new LlmApiException(LlmApiException.Type.TIMEOUT,
                    "LLM provider attempts exceeded overall timeout", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED,
                    "LLM provider execution interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED,
                    "LLM provider execution failed", exception.getCause());
        }
    }

    private <T> T executeProtected(String providerName, Supplier<T> operation) {
        String name = normalize(providerName);
        Supplier<T> timeouts = Retry.decorateSupplier(Retry.of(name + "-timeout", timeoutRetry), operation);
        Supplier<T> rateLimits = Retry.decorateSupplier(Retry.of(name + "-rate-limit", rateLimitRetry), timeouts);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(name);
        return CircuitBreaker.decorateSupplier(breaker, rateLimits).get();
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
