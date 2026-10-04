package com.hify.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowHttpClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<String> contentType = new AtomicReference<>("text/plain; charset=utf-8");
    private final AtomicInteger size = new AtomicInteger(12);
    private final AtomicBoolean chunked = new AtomicBoolean(), stall = new AtomicBoolean();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService workers;
    private String endpoint;
    private final List<OkHttpClient> clients = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/read", exchange -> {
            received.incrementAndGet();
            try {
                if (contentType.get() != null) exchange.getResponseHeaders().set("Content-Type", contentType.get());
                exchange.sendResponseHeaders(200, chunked.get() ? 0 : size.get());
                if (stall.get()) try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                exchange.getResponseBody().write("x".repeat(size.get()).getBytes(StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/read";
    }
    @AfterEach void cleanup() {
        release.countDown(); server.stop(0); workers.shutdownNow();
        for (OkHttpClient client : clients) { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
    }
    private WorkflowHttpClient client(String target, OkHttpClient transport) throws Exception {
        clients.add(transport);
        return new WorkflowHttpClient(json.writeValueAsString(List.of(target)), json, new CredentialReferencePolicy("{}", json), transport);
    }
    private OkHttpClient transport() { return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).readTimeout(Duration.ofSeconds(2)).build(); }
    @Test void exactByteLimitWorksForLengthAndChunkedAndOverflowNeverReturnsPartialBody() throws Exception {
        var client = client(endpoint, transport());
        for (boolean streaming : List.of(false, true)) {
            chunked.set(streaming); size.set(32768);
            assertThat(client.get(endpoint, "", Map.of(), ExecutionControl.none())).hasSize(32768);
            size.set(32769);
            assertThatThrownBy(() -> client.get(endpoint, "", Map.of(), ExecutionControl.none())).isInstanceOf(BizException.class);
        }
        assertThat(received).hasValue(4);
    }
    @Test void responseTypeIsExplicitAndCharsetCannotContradictUtf8() throws Exception {
        var client = client(endpoint, transport());
        for (String type : List.of("application/json", "application/problem+json", "text/plain", "text/plain; charset=UTF-8")) {
            contentType.set(type);
            assertThat(client.get(endpoint, "", Map.of(), ExecutionControl.none())).hasSize(12);
        }
        for (String type : Arrays.asList(null, "text/html", "application/octet-stream", "application/json; charset=ISO-8859-1")) {
            contentType.set(type);
            assertThatThrownBy(() -> client.get(endpoint, "", Map.of(), ExecutionControl.none())).isInstanceOf(BizException.class);
        }
        assertThat(received).hasValue(8);
    }
    @Test void socketTimeoutIsNodeFailureNotParentDeadlineAndDoesNotRetry() throws Exception {
        var client = client(endpoint, transport().newBuilder().readTimeout(Duration.ofMillis(150)).build());
        var parent = ExecutionControl.withTimeout(Duration.ofSeconds(20), () -> false);
        assertThat(client.get(endpoint, "", Map.of(), parent)).hasSize(12);
        stall.set(true);
        assertThatThrownBy(() -> client.get(endpoint, "", Map.of(), parent))
                .isInstanceOf(BizException.class).isNotInstanceOf(WorkflowControl.DeadlineExceeded.class);
        assertThat(parent.isExpired()).isFalse(); assertThat(received).hasValue(2);
    }
    @Test void dnsRebindingIsRejectedBeforeAnySocketAndLiteralGrantsCannotAllowMetadata() throws Exception {
        String host = "http://api.example.com:" + server.getAddress().getPort() + "/read";
        AtomicInteger lookups = new AtomicInteger();
        var transport = transport().newBuilder().dns(name -> {
            lookups.incrementAndGet(); return List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"));
        }).build();
        var client = client(host, transport);
        assertThatThrownBy(() -> client.get(host, "", Map.of(), ExecutionControl.none())).isInstanceOf(BizException.class);
        assertThat(lookups).hasValue(1); assertThat(received).hasValue(0);
        for (String ip : List.of("169.254.169.254", "100.100.100.200", "[64:ff9b::a9fe:a9fe]", "[::7f00:1]")) {
            String forbidden = "http://" + ip + "/read";
            var explicit = client(forbidden, transport());
            assertThatThrownBy(() -> explicit.requireAllowed(forbidden, "")).isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> WorkflowHttpClient.canonical("http://[::ffff:127.0.0.1]/read")).isInstanceOf(BizException.class);
        assertThat(received).hasValue(0);
    }
}
