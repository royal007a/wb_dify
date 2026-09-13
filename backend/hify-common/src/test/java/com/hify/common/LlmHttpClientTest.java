package com.hify.common;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
}

