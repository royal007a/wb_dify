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
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmHttpClientTest {
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
        server.shutdown();
        executor.shutdownNow();
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
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: waiting\n\n")
                .setBodyDelay(5, TimeUnit.SECONDS));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(80);
                cancelled.set(true);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.streamAndAwait(server.url("/cancel-stream").toString(), Map.of(), "{}",
                ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get), callback(new AtomicReference<>(),
                        new CountDownLatch(1))))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);
        canceller.join();
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
