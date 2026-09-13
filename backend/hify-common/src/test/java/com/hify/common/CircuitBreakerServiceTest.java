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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerServiceTest {
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom().minimumNumberOfCalls(2).slidingWindowSize(2)
                    .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(30)).build());
    private final CircuitBreakerService service = new CircuitBreakerService(
            registry, 3, Duration.ZERO, 3, Duration.ZERO);

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

    @Test
    void stopsAttemptChainAtOverallDeadlineAndInterruptsTheWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch interrupted = new CountDownLatch(1);
        CircuitBreakerService deadlineService = new CircuitBreakerService(
                registry, executor, Duration.ofMillis(50),
                3, Duration.ZERO, 3, Duration.ZERO);
        try {
            assertThatThrownBy(() -> deadlineService.execute("slow-provider", () -> {
                try {
                    Thread.sleep(5_000);
                    return "too late";
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new LlmApiException(LlmApiException.Type.REQUEST_FAILED, "interrupted", exception);
                }
            })).isInstanceOfSatisfying(LlmApiException.class,
                    failure -> assertThat(failure.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
