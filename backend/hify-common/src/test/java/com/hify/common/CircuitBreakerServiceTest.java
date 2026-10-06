package com.hify.common;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerServiceTest {
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom().minimumNumberOfCalls(2).slidingWindowSize(2)
                    .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build());
    private final CircuitBreakerService service = new CircuitBreakerService(
            registry, 3, Duration.ZERO, 3, Duration.ZERO);

    @Test void fiveCancelledOperationsMustNotOpenTheProviderBreaker() {
        for (int i = 0; i < 5; i++) {
            AtomicBoolean cancelled = new AtomicBoolean();
            try {
                service.execute("user-cancel", ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get), () -> {
                    cancelled.set(true);
                    return "late result";
                });
            } catch (RuntimeException ignored) { /* inspect breaker, not only caller exception */ }
        }
        var breaker = registry.circuitBreaker("provider-user-cancel");
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(service.execute("user-cancel", () -> "next user works")).isEqualTo("next user works");
    }

    @Test void nestedHttpPoolRejectionMustNotCountAsProviderFailureOrRetry() {
        AtomicInteger submissions = new AtomicInteger();
        LlmHttpClient rejected = new LlmHttpClient(task -> {
            submissions.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("ThreadPoolExecutor@private-state");
        });
        for (int i = 0; i < 5; i++) {
            try { service.execute("local-http-capacity", () -> rejected.post("http://127.0.0.1:1/unreached", java.util.Map.of(), "{}")); }
            catch (RuntimeException e) { assertThat(e).hasMessageNotContaining("private-state"); }
        }
        var breaker = registry.circuitBreaker("provider-local-http-capacity");
        assertThat(breaker.getState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(submissions).hasValue(5);
    }

    @Test void cancellationDuringTimeoutFailureAlsoDoesNotPoisonTheBreaker() {
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThatThrownBy(() -> service.execute("cancel-race", ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get), () -> {
            cancelled.set(true);
            throw new LlmApiException(LlmApiException.Type.TIMEOUT, "late upstream error");
        })).isInstanceOf(ExecutionCancelledException.class);
        assertThat(registry.circuitBreaker("provider-cancel-race").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void retriesTimeoutButNeverRetriesAuthenticationFailure() {
        AtomicInteger timeoutCalls = new AtomicInteger();
        String value = service.execute("timeout-provider", () -> {
            if (timeoutCalls.incrementAndGet() < 3) {
                throw new LlmApiException(LlmApiException.Type.TIMEOUT, "slow");
            }
            return "ok";
        });
        assertThat(value).isEqualTo("ok");
        assertThat(timeoutCalls).hasValue(3);

        AtomicInteger authCalls = new AtomicInteger();
        assertThatThrownBy(() -> service.execute("auth-provider", () -> {
            authCalls.incrementAndGet();
            throw new LlmApiException(LlmApiException.Type.AUTH_FAILED, "bad key");
        })).isInstanceOf(LlmApiException.class);
        assertThat(authCalls).hasValue(1);
    }

    @Test void genuineUpstreamTimeoutAndUnavailableStillOpenOnlyTheirOwnBreaker() {
        for (LlmApiException.Type type : java.util.List.of(LlmApiException.Type.TIMEOUT, LlmApiException.Type.PROVIDER_UNAVAILABLE)) {
            AtomicInteger attempts = new AtomicInteger();
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> service.execute(type.name(), () -> {
                    attempts.incrementAndGet();
                    throw new LlmApiException(type, "upstream failed");
                })).isInstanceOf(LlmApiException.class);
            }
            assertThat(attempts).hasValue(6);
            assertThat(registry.circuitBreaker("provider-" + type.name()).getState())
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        }
        assertThat(service.execute("healthy", () -> "ok")).isEqualTo("ok");
    }

    @Test void cancelledHalfOpenProbesReleasePermissionWithoutSuccessCredit() {
        var breaker = registry.circuitBreaker("provider-half-open");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        for (int i = 0; i < 20; i++) {
            AtomicBoolean cancelled = new AtomicBoolean();
            assertThatThrownBy(() -> service.execute("half-open", ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get), () -> {
                cancelled.set(true);
                return "discarded";
            })).isInstanceOf(ExecutionCancelledException.class);
        }
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(service.execute("half-open", () -> "real probe")).isEqualTo("real probe");
    }

    @Test void outerExecutorRejectionIsLocalAndSanitized() {
        CircuitBreakerService rejected = new CircuitBreakerService(registry, task -> {
            throw new java.util.concurrent.RejectedExecutionException("ThreadPoolExecutor@private-state");
        }, Duration.ofSeconds(5), 3, Duration.ZERO, 3, Duration.ZERO);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> rejected.execute("outer-capacity", attempts::incrementAndGet))
                .isInstanceOf(ExecutionRejectedException.class).hasNoCause().hasMessageNotContaining("private-state");
        assertThat(attempts).hasValue(0);
        assertThat(registry.circuitBreaker("provider-outer-capacity").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void stopsAttemptChainAtOverallDeadlineAndInterruptsTheWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        long observedStart = System.nanoTime();
        CircuitBreakerService deadlineService = new CircuitBreakerService(
                registry, executor, Duration.ofMillis(50),
                3, Duration.ZERO, 3, Duration.ZERO);
        try {
            assertThatThrownBy(() -> deadlineService.execute("slow-provider", () -> {
                entered.incrementAndGet();
                System.err.println("deadline-test phase=operation-entered elapsedNanos=" + (System.nanoTime()-observedStart));
                try {
                    Thread.sleep(5_000);
                    return "too late";
                } catch (InterruptedException exception) {
                    System.err.println("deadline-test phase=operation-interrupted elapsedNanos=" + (System.nanoTime()-observedStart));
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED, "interrupted", exception);
                }
            })).isInstanceOfSatisfying(LlmApiException.class,
                    failure -> assertThat(failure.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
            System.err.println("deadline-test phase=caller-timeout entered=" + entered.get()
                    + " elapsedNanos=" + (System.nanoTime()-observedStart));
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).as("worker entered %s times", entered.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void overallDeadlineWhileQueuedDoesNotStartOrSampleAnOperation() {
        var queued=new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var calls=new AtomicInteger();
        var deadlineService=new CircuitBreakerService(registry, queued::set, Duration.ofMillis(50),
                3, Duration.ZERO, 3, Duration.ZERO);
        assertThatThrownBy(()->deadlineService.execute("expired-in-queue", calls::incrementAndGet))
                .isInstanceOfSatisfying(LlmApiException.class,
                        failure->assertThat(failure.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
        assertThat(queued.get()).isNotNull();
        queued.get().run();
        assertThat(calls).hasValue(0);
        var metrics=registry.circuitBreaker("provider-expired-in-queue").getMetrics();
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
        assertThat(metrics.getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void preCancelledCallDoesNotSubmitWorker() {
        AtomicInteger submissions = new AtomicInteger();
        CircuitBreakerService guarded = new CircuitBreakerService(registry, r -> submissions.incrementAndGet(),
                Duration.ofSeconds(10), 3, Duration.ZERO, 3, Duration.ZERO);
        assertThatThrownBy(() -> guarded.execute("cancelled", ExecutionControl.withTimeout(Duration.ofSeconds(1), () -> true), () -> "no"))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(submissions).hasValue(0);
    }

    @Test
    void checksCancellationInsideQueuedWorkerBeforeExecutingOperation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        CircuitBreakerService guarded = new CircuitBreakerService(registry, r -> { cancelled.set(true); r.run(); },
                Duration.ofSeconds(10), 3, Duration.ZERO, 3, Duration.ZERO);
        assertThatThrownBy(() -> guarded.execute("queued", ExecutionControl.withTimeout(Duration.ofSeconds(1), cancelled::get),
                calls::incrementAndGet)).isInstanceOf(ExecutionCancelledException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void doesNotRetryAfterCancellationDuringPreviousAttempt() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> service.execute("cancel-between-retries",
                ExecutionControl.withTimeout(Duration.ofSeconds(3), cancelled::get), () -> {
                    calls.incrementAndGet(); cancelled.set(true);
                    throw new LlmApiException(LlmApiException.Type.TIMEOUT, "retryable failure");
                })).isInstanceOf(ExecutionCancelledException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancellationInterruptsWorkerBeforeTestCleanup() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        CircuitBreakerService guarded = new CircuitBreakerService(registry, worker, Duration.ofSeconds(20),
                3, Duration.ZERO, 3, Duration.ZERO);
        try {
            var call = caller.submit(() -> guarded.execute("cancel-worker",
                    ExecutionControl.withTimeout(Duration.ofSeconds(10), cancelled::get), () -> {
                        entered.countDown();
                        try { release.await(); }
                        catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return "late";
                    }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionCancelledException.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown(); worker.shutdownNow(); caller.shutdownNow();
        }
    }
}
