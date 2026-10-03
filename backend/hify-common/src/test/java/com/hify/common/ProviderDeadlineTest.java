package com.hify.common;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ProviderDeadlineTest {
    CircuitBreakerRegistry registry() {return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(5).slidingWindowSize(5).failureRateThreshold(50).permittedNumberOfCallsInHalfOpenState(3)
            .waitDurationInOpenState(Duration.ofSeconds(30)).build());}

    @Test void realStalledHttpCountsModelDeadlineAndEventuallyOpensBreaker() throws Exception {
        var registry=registry();
        ExecutorService attempts=Executors.newSingleThreadExecutor(),httpWorkers=Executors.newFixedThreadPool(2);
        ExecutorService handlers=Executors.newCachedThreadPool();
        CountDownLatch release=new CountDownLatch(1);
        AtomicInteger received=new AtomicInteger();
        HttpServer remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        remote.setExecutor(handlers);
        remote.createContext("/hang",exchange->{
            exchange.getRequestBody().readAllBytes();received.incrementAndGet();
            try {release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        remote.start();
        var client=new LlmHttpClient(httpWorkers);
        var service=new CircuitBreakerService(registry,attempts,Duration.ofSeconds(1),1,Duration.ZERO,1,Duration.ZERO);
        String url="http://127.0.0.1:"+remote.getAddress().getPort()+"/hang";
        try {
            for(int i=0;i<5;i++) {
                assertThatThrownBy(()->service.execute("stalled",()->client.post(url,Map.of(),"{}")))
                        .isInstanceOfSatisfying(LlmApiException.class,e->assertThat(e.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
                // Force the prior worker's classification to finish before another attempt.
                attempts.submit(()->{}).get(2,TimeUnit.SECONDS);
            }
            assertThat(received).hasValue(5);
            var breaker=registry.circuitBreaker("provider-stalled");
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThatThrownBy(()->service.execute("stalled",()->client.post(url,Map.of(),"{}")))
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(received).hasValue(5);
        } finally {release.countDown();remote.stop(0);handlers.shutdownNow();attempts.shutdownNow();httpWorkers.shutdownNow();}
    }

    @Test void modelDeadlineSettlesHalfOpenBeforeAnUncooperativeOperationReturns() throws Exception {
        var registry=registry();var breaker=registry.circuitBreaker("provider-uncooperative");
        breaker.transitionToOpenState();breaker.transitionToHalfOpenState();
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var service=new CircuitBreakerService(registry,worker,Duration.ofMillis(150),1,Duration.ZERO,1,Duration.ZERO);
        try {
            assertThatThrownBy(()->service.execute("uncooperative",()->{
                entered.countDown();
                while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* emulate a driver ignoring interruption */}}
                return "late success";
            })).isInstanceOf(LlmApiException.class);
            assertThat(entered.getCount()).isZero();
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            release.countDown();worker.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        } finally {release.countDown();worker.shutdownNow();}
    }

    @Test void runDeadlineReleasesHalfOpenEvenWhenTheWorkerIgnoresInterruption() throws Exception {
        var registry=registry();var breaker=registry.circuitBreaker("provider-run-budget");
        breaker.transitionToOpenState();breaker.transitionToHalfOpenState();
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch release=new CountDownLatch(1);
        var service=new CircuitBreakerService(registry,worker,Duration.ofSeconds(10),1,Duration.ZERO,1,Duration.ZERO);
        try {
            assertThatThrownBy(()->service.execute("run-budget", ExecutionControl.withTimeout(Duration.ofMillis(150),()->false),()->{
                while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}
                return "late";
            })).isInstanceOf(LlmApiException.class);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
            // All three probe permits are available before the blocked worker has returned.
            for(int i=0;i<3;i++) assertThat(breaker.tryAcquirePermission()).isTrue();
            for(int i=0;i<3;i++) breaker.releasePermission();
            release.countDown();worker.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        } finally {release.countDown();worker.shutdownNow();}
    }

    @Test void queuedModelDeadlineIsNotAProviderSample() {
        var registry=registry();AtomicReference<Runnable> queued=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();
        var service=new CircuitBreakerService(registry,queued::set,Duration.ofMillis(50),1,Duration.ZERO,1,Duration.ZERO);
        assertThatThrownBy(()->service.execute("queue-only",calls::incrementAndGet)).isInstanceOf(LlmApiException.class);
        queued.get().run();
        assertThat(calls).hasValue(0);
        assertThat(registry.circuitBreaker("provider-queue-only").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test void wrappedCancellationIsIgnoredButInterruptedIoTimeoutStillCounts() {
        var registry=registry();var service=new CircuitBreakerService(registry,1,Duration.ZERO,1,Duration.ZERO);
        assertThatThrownBy(()->service.execute("wrapped",()->{
            throw new CompletionException(new ExecutionCancelledException("cancelled"));
        })).isInstanceOf(CompletionException.class);
        assertThat(registry.circuitBreaker("provider-wrapped").getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThatThrownBy(()->service.execute("io-timeout",()->{
            throw new LlmApiException(LlmApiException.Type.TIMEOUT,"read deadline",new java.net.SocketTimeoutException());
        })).isInstanceOf(LlmApiException.class);
        assertThat(registry.circuitBreaker("provider-io-timeout").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test void cancellationWhileWaitingForRetryIsNotAFailedSample() throws Exception {
        var registry=registry();ExecutorService worker=Executors.newSingleThreadExecutor(),caller=Executors.newSingleThreadExecutor();
        AtomicBoolean cancelled=new AtomicBoolean();CountDownLatch first=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        var service=new CircuitBreakerService(registry,worker,Duration.ofSeconds(10),3,Duration.ofSeconds(5),1,Duration.ZERO);
        try {
            var call=caller.submit(()->service.execute("retry-cancel",ExecutionControl.withTimeout(Duration.ofSeconds(10),cancelled::get),()->{
                calls.incrementAndGet();first.countDown();throw new LlmApiException(LlmApiException.Type.TIMEOUT,"upstream");
            }));
            assertThat(first.await(2,TimeUnit.SECONDS)).isTrue();cancelled.set(true);
            assertThatThrownBy(()->call.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionCancelledException.class);
            worker.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertThat(calls).hasValue(1);
            assertThat(registry.circuitBreaker("provider-retry-cancel").getMetrics().getNumberOfBufferedCalls()).isZero();
        } finally {worker.shutdownNow();caller.shutdownNow();}
    }
}
