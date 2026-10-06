package com.hify.common;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmHttpClientTest {
    @Test void completedHttpReplyStillRespectsLocalBudgetOnParentClock() {
        var clock=new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE-10);
        var parent=ExecutionControl.withTimeout(Duration.ofSeconds(300),()->false,clock::get);
        var http=new LlmHttpClient(task->{task.run();clock.addAndGet(Duration.ofSeconds(65).toNanos());});
        server.enqueue(new MockResponse().setBody("late"));
        assertThatThrownBy(()->http.post(server.url("/clock-late").toString(),Map.of(),"{}",parent))
                .isInstanceOfSatisfying(LlmApiException.class,
                        failure->assertThat(failure.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(parent.isExpired()).isFalse();
        var timely=new LlmHttpClient(task->{task.run();clock.addAndGet(Duration.ofSeconds(64).toNanos());});
        server.enqueue(new MockResponse().setBody("on time"));
        assertThat(timely.post(server.url("/clock-timely").toString(),Map.of(),"{}",parent)).isEqualTo("on time");
    }

    @Test void streamStopsFurtherDeltasAtItsOwnDeadlineWhileParentRemainsActive() {
        var clock=new java.util.concurrent.atomic.AtomicLong(-100);
        var parent=ExecutionControl.withTimeout(Duration.ofSeconds(300),()->false,clock::get);
        var deltas=new AtomicInteger();
        server.enqueue(new MockResponse().setHeader("Content-Type","text/event-stream")
                .setBody("data: first\n\ndata: forbidden-late\n\n"));
        assertThatThrownBy(()->client.streamAndAwait(server.url("/clock-stream").toString(),Map.of(),"{}",parent,
                new LlmHttpClient.StreamCallback(){
                    @Override public void onEvent(String id,String type,String data){
                        deltas.incrementAndGet();clock.addAndGet(Duration.ofSeconds(125).toNanos());
                    }
                    @Override public void onClosed(){}
                    @Override public void onFailure(LlmApiException failure){}
                })).isInstanceOfSatisfying(LlmApiException.class,
                        failure->assertThat(failure.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
        assertThat(deltas).hasValue(1);
        assertThat(parent.isExpired()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private MockWebServer server;
    private java.util.concurrent.ExecutorService executor;
    private LlmHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");
        System.setProperty("https.nonProxyHosts", "localhost|127.*|[::1]");
        System.setProperty("socksNonProxyHosts", "localhost|127.*|[::1]");
        server = new MockWebServer();
        server.start();
        executor = Executors.newSingleThreadExecutor();
        client = new LlmHttpClient(executor);
    }

    @AfterEach
    void tearDown() throws Exception {
        org.slf4j.MDC.clear();
        try { executor.shutdownNow(); }
        finally { server.shutdown(); }
    }

    @Test
    void streamingCallbacksCarryCapturedRequestIdWithoutSecrets() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: synthetic\n\n"));
        var observed = new AtomicReference<Map<String, String>>();
        var closedContext = new AtomicReference<Map<String, String>>();
        var failure = new AtomicReference<LlmApiException>();
        var done = new CountDownLatch(1);
        org.slf4j.MDC.put("requestId", "stream-request");
        org.slf4j.MDC.put("syntheticSecret", "not-forwarded");
        client.stream(server.url("/correlated-stream").toString(), Map.of(), "{}", new LlmHttpClient.StreamCallback() {
            @Override public void onEvent(String id, String type, String data) {
                observed.set(org.slf4j.MDC.getCopyOfContextMap());
                org.slf4j.MDC.put("callback-local", "must-not-reach-next-callback");
            }
            @Override public void onClosed() { closedContext.set(org.slf4j.MDC.getCopyOfContextMap()); done.countDown(); }
            @Override public void onFailure(LlmApiException e) { failure.set(e); done.countDown(); }
        });
        org.slf4j.MDC.put("requestId", "caller-changed");
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(observed.get()).isEqualTo(Map.of("requestId", "stream-request"));
        assertThat(closedContext.get()).isEqualTo(Map.of("requestId", "stream-request"));
        assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("caller-changed");
    }

    @Test
    void malformedEventFailureCallbackGetsFreshCapturedScope() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: malformed-synthetic\n\n"));
        var firstFailureContext = new AtomicReference<Map<String, String>>();
        var done = new CountDownLatch(1);
        org.slf4j.MDC.put("requestId", "original-stream");
        client.stream(server.url("/invalid-event").toString(), Map.of(), "{}", new LlmHttpClient.StreamCallback() {
            @Override public void onEvent(String id, String type, String data) {
                org.slf4j.MDC.put("requestId", "callback-changed");
                org.slf4j.MDC.put("syntheticSecret", "must-not-reach-failure-callback");
                throw new IllegalArgumentException("synthetic parse failure");
            }
            @Override public void onClosed() {}
            @Override public void onFailure(LlmApiException exception) {
                firstFailureContext.compareAndSet(null, org.slf4j.MDC.getCopyOfContextMap());
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstFailureContext.get()).isEqualTo(Map.of("requestId", "original-stream"));
        assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("original-stream");
    }

    @Test
    void streamingFailureCallbackCarriesRequestId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        var observed = new AtomicReference<Map<String, String>>();
        var failure = new AtomicReference<LlmApiException>();
        var done = new CountDownLatch(1);
        org.slf4j.MDC.put("requestId", "failed-stream-request");
        client.stream(server.url("/failed-correlated-stream").toString(), Map.of(), "{}", new LlmHttpClient.StreamCallback() {
            @Override public void onEvent(String id, String type, String data) {}
            @Override public void onClosed() { done.countDown(); }
            @Override public void onFailure(LlmApiException e) {
                observed.set(org.slf4j.MDC.getCopyOfContextMap()); failure.set(e); done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNotNull();
        assertThat(failure.get().type()).isEqualTo(LlmApiException.Type.PROVIDER_UNAVAILABLE);
        assertThat(observed.get()).isEqualTo(Map.of("requestId", "failed-stream-request"));
    }

    @Test
    void postsJsonOnLlmExecutorAndClassifiesAuthenticationFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        assertThat(client.post(server.url("/chat/completions").toString(),
                Map.of("Authorization", "Bearer secret"), "{}"))
                .isEqualTo("{\"ok\":true}");
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer secret");

        server.enqueue(new MockResponse().setResponseCode(401).setBody("denied"));
        assertThatThrownBy(() -> client.post(server.url("/chat/completions").toString(), Map.of(), "{}"))
                .isInstanceOfSatisfying(LlmApiException.class,
                        failure -> assertThat(failure.type()).isEqualTo(LlmApiException.Type.AUTH_FAILED))
                .hasMessageNotContaining("denied");
    }

    @Test
    void streamsSseEventsAndExposesCancelableHandle() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("id: 7\nevent: message.delta\ndata: hello\n\n"));
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> event = new AtomicReference<>();
        var handle = client.stream(server.url("/stream").toString(), Map.of(), "{}",
                new LlmHttpClient.StreamCallback() {
                    @Override public void onEvent(String id, String type, String data) {
                        event.set(id + ":" + type + ":" + data);
                    }
                    @Override public void onClosed() { closed.countDown(); }
                    @Override public void onFailure(LlmApiException exception) { closed.countDown(); }
                });
        assertThat(closed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(event).hasValue("7:message.delta:hello");
        handle.cancel();
    }

    @Test
    void propagatesRunCancellationIntoBlockingPost() throws Exception {
        server.enqueue(new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS)
                .setResponseCode(200).setBody("{\"ok\":true}"));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(50);
                cancelled.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.post(server.url("/slow").toString(), Map.of(), "{}",
                ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get)))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);
        canceller.join();
    }

    @Test
    void preCancelledPostNeverSubmitsToExecutor() {
        AtomicInteger submitted = new AtomicInteger();
        LlmHttpClient guarded = new LlmHttpClient(task -> submitted.incrementAndGet());
        assertThatThrownBy(() -> guarded.post(server.url("/never").toString(), Map.of(), "{}",
                ExecutionControl.withTimeout(Duration.ofSeconds(1), () -> true)))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(submitted).hasValue(0);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void expiredPostNeverSubmitsToExecutor() {
        AtomicInteger submitted = new AtomicInteger();
        LlmHttpClient guarded = new LlmHttpClient(task -> submitted.incrementAndGet());
        var expired = ExecutionControl.withTimeout(Duration.ofNanos(1), () -> false);
        assertThat(expired.isExpired()).isTrue();
        assertThatThrownBy(() -> guarded.post(server.url("/never").toString(), Map.of(), "{}", expired))
                .isInstanceOfSatisfying(LlmApiException.class, e -> assertThat(e.type()).isEqualTo(LlmApiException.Type.TIMEOUT));
        assertThat(submitted).hasValue(0);
    }

    @Test
    void saturatedExecutorFailsWithoutNetworkOrFallbackOnCaller() {
        LlmHttpClient saturated = new LlmHttpClient(task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThatThrownBy(() -> saturated.post(server.url("/never").toString(), Map.of(), "{}"))
                .isInstanceOfSatisfying(LlmApiException.class, e -> assertThat(e.type()).isEqualTo(LlmApiException.Type.REQUEST_FAILED));
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void preCancelledStreamNeverOpensNetwork() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: forbidden\n\n"));
        assertThatThrownBy(() -> client.streamAndAwait(server.url("/never").toString(), Map.of(), "{}",
                ExecutionControl.withTimeout(Duration.ofSeconds(1), () -> true), callback(new AtomicReference<>(), new CountDownLatch(1))))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void cancelledBlockingPostReleasesSingleWorkerBeforeCleanup() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        var caller = Executors.newSingleThreadExecutor();
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            var call = caller.submit(() -> client.post(server.url("/wait-until-cancelled").toString(), Map.of(), "{}",
                    ExecutionControl.withTimeout(Duration.ofSeconds(15), cancelled::get)));
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            cancelled.set(true);
            assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionCancelledException.class);
            assertThat(executor.submit(() -> "worker available").get(2, TimeUnit.SECONDS)).isEqualTo("worker available");
        } finally { caller.shutdownNow(); }
    }

    @Test
    void classifiesStreamingAuthenticationRateLimitAndProviderFailures() throws Exception {
        assertStreamFailure(401, LlmApiException.Type.AUTH_FAILED);
        assertStreamFailure(429, LlmApiException.Type.RATE_LIMITED);
        assertStreamFailure(503, LlmApiException.Type.PROVIDER_UNAVAILABLE);
    }

    @Test
    void classifiesMidStreamDisconnectAsRequestFailure() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: first-token\n\ndata: never-complete\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        AtomicReference<LlmApiException> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        client.stream(server.url("/half-stream").toString(), Map.of(), "{}", callback(failure, completed));

        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNotNull();
        assertThat(failure.get().type()).isEqualTo(LlmApiException.Type.REQUEST_FAILED);
    }

    @Test
    void propagatesRunCancellationIntoStreamingCall() throws Exception {
        // Hold an open socket without a server-side sleep that races MockWebServer.shutdown's timeout.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var call = caller.submit(() -> client.streamAndAwait(server.url("/cancel-stream").toString(), Map.of(), "{}",
                    ExecutionControl.withTimeout(Duration.ofSeconds(15), cancelled::get),
                    callback(new AtomicReference<>(), callbackFinished)));
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
            cancelled.set(true);
            assertThatThrownBy(() -> call.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionCancelledException.class);
            assertThat(callbackFinished.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { caller.shutdownNow(); }
    }

    @Test
    void refusesRedirectsForBlockingAndStreamingRequests() throws Exception {
        String target = server.url("/private-target").toString();
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        assertThatThrownBy(() -> client.post(server.url("/redirect-post").toString(), Map.of(), "{}"))
                .isInstanceOfSatisfying(LlmApiException.class,
                        failure -> assertThat(failure.type()).isEqualTo(LlmApiException.Type.REQUEST_FAILED));
        assertThat(server.getRequestCount()).isEqualTo(1);

        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", target));
        AtomicReference<LlmApiException> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        client.stream(server.url("/redirect-stream").toString(), Map.of(), "{}", callback(failure, completed));
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNotNull();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    private void assertStreamFailure(int status, LlmApiException.Type expected) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(status).setBody("not exposed"));
        AtomicReference<LlmApiException> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        client.stream(server.url("/stream-" + status).toString(), Map.of(), "{}", callback(failure, completed));
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNotNull();
        assertThat(failure.get().type()).isEqualTo(expected);
        assertThat(failure.get().getMessage()).doesNotContain("not exposed");
    }

    private LlmHttpClient.StreamCallback callback(AtomicReference<LlmApiException> failure,
                                                   CountDownLatch completed) {
        return new LlmHttpClient.StreamCallback() {
            @Override public void onEvent(String id, String type, String data) {}
            @Override public void onClosed() { completed.countDown(); }
            @Override public void onFailure(LlmApiException exception) {
                failure.compareAndSet(null, exception);
                completed.countDown();
            }
        };
    }
}
