package com.hify.common;

import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ExecutionShutdownTest {
    @Test void shutdownInterruptionIsNotUserCancellation() {
        ExecutionControl control=ExecutionControl.withTimeout(Duration.ofSeconds(5),()->false).withShutdown(()->true);
        Thread.currentThread().interrupt();
        try {
            assertThat(control.isCancelled()).isFalse();
            assertThatThrownBy(control::throwIfCancelled).isInstanceOf(ExecutionSuspendedException.class);
        } finally {Thread.interrupted();}
    }
    @Test void explicitCancellationWinsAndDeadlineIsNotResetByShutdownBinding() throws Exception {
        ExecutionControl control=ExecutionControl.withTimeout(Duration.ofMillis(1),()->true);
        Thread.sleep(5);
        ExecutionControl bound=control.withShutdown(()->true);
        assertThat(bound.isExpired()).isTrue();
        assertThat(bound.isSuspended()).isFalse();
        assertThatThrownBy(bound::throwIfCancelled).isInstanceOf(ExecutionCancelledException.class);
    }
    @Test void childContextCloseDoesNotStopParentContext() {
        ExecutionLifecycle lifecycle=new ExecutionLifecycle();
        try(var parent=new GenericApplicationContext();var child=new GenericApplicationContext()){
            lifecycle.setApplicationContext(parent);
            lifecycle.onApplicationEvent(new ContextClosedEvent(child));
            assertThat(lifecycle.isStopping()).isFalse();
            lifecycle.onApplicationEvent(new ContextClosedEvent(parent));
            assertThat(lifecycle.isStopping()).isTrue();
        }
        assertThat(new ExecutionLifecycle().isStopping()).isFalse();
    }
    @Test void shutdownReleasesHalfOpenPermitWithoutSuccessOrFailureSample() throws Exception {
        var registry=CircuitBreakerRegistry.of(CircuitBreakerConfig.custom().permittedNumberOfCallsInHalfOpenState(1).build());
        var breaker=registry.circuitBreaker("provider-shutdown");breaker.transitionToOpenState();breaker.transitionToHalfOpenState();
        var workers=Executors.newSingleThreadExecutor();var callers=Executors.newSingleThreadExecutor();
        var stopping=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var service=new CircuitBreakerService(registry,workers,Duration.ofSeconds(10),1,Duration.ZERO,1,Duration.ZERO);
        try {
            var result=callers.submit(()->service.execute("shutdown",ExecutionControl.withTimeout(Duration.ofSeconds(10),()->false).withShutdown(stopping::get),()->{
                entered.countDown();
                try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExecutionCancelledException("interrupted");}
                return "unused";
            }));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();stopping.set(true);
            assertThatThrownBy(()->result.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionSuspendedException.class);
            workers.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
            assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
            assertThat(breaker.tryAcquirePermission()).isTrue();breaker.releasePermission();
        } finally {release.countDown();workers.shutdownNow();callers.shutdownNow();}
    }
}
