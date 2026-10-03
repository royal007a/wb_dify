package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import com.hify.mcp.application.McpCredentialResolver;
import com.hify.mcp.application.McpEndpointGuard;
import com.hify.mcp.application.McpProtocolClient;
import com.hify.mcp.domain.McpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpProtocolClientReliabilityTest {
    private HttpServer remote;

    @AfterEach
    void stop() {
        if (remote != null) remote.stop(0);
    }

    @Test
    void classifiesUnavailableServerWithoutFollowingRedirects() throws Exception {
        String endpoint = serve(exchange -> respond(exchange, 503, "unavailable"));

        assertThatThrownBy(() -> client().call(server(endpoint), "lookup", new ObjectMapper().valueToTree(Map.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageNotContaining("unavailable");
    }

    @Test
    void enforcesRunDeadlineDuringBlockingMcpCall() throws Exception {
        String endpoint = serve(exchange -> protocol(exchange, 2_000));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client().call(server(endpoint), "lookup",
                new ObjectMapper().valueToTree(Map.of("id", "A-1")),
                ExecutionControl.withTimeout(Duration.ofMillis(150), () -> false)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("timed out");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);
    }

    @Test
    void propagatesRunCancellationDuringBlockingMcpCall() throws Exception {
        String endpoint = serve(exchange -> protocol(exchange, 2_000));
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

        assertThatThrownBy(() -> client().call(server(endpoint), "lookup",
                new ObjectMapper().valueToTree(Map.of("id", "A-1")),
                ExecutionControl.withTimeout(Duration.ofSeconds(5), cancelled::get)))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);
        canceller.join();
    }

    private McpProtocolClient client() {
        return new McpProtocolClient(new ObjectMapper(), new McpEndpointGuard(true),
                new McpCredentialResolver(null, new com.hify.common.CredentialReferencePolicy("", new ObjectMapper())));
    }

    @Test void explicitReferenceCanOnlyReachItsApprovedEndpoint() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
        String endpoint = serve(exchange -> {
            received.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            protocol(exchange, 0);
        });
        var json = new ObjectMapper();
        String ref = "system:hify.test.bound-mcp";
        var policy = new com.hify.common.CredentialReferencePolicy(
                json.writeValueAsString(Map.of(ref, java.util.List.of(endpoint))), json);
        var client = new McpProtocolClient(json, new McpEndpointGuard(true), new McpCredentialResolver(null, policy));
        System.setProperty("hify.test.bound-mcp", "fake-approved-credential");
        try {
            var allowed = new McpServer("test", "test", endpoint, ref, true, Instant.now());
            assertThat(client.call(allowed, "lookup", json.createObjectNode()).error()).isFalse();
            assertThat(authorization.get()).isEqualTo("Bearer fake-approved-credential");
            int before = received.get();
            var changed = new McpServer("test", "test", endpoint + "/other", ref, true, Instant.now());
            assertThatThrownBy(() -> client.call(changed, "lookup", json.createObjectNode()))
                    .isInstanceOf(BizException.class).hasMessageContaining("not approved");
            assertThat(received.get()).isEqualTo(before);
        } finally { System.clearProperty("hify.test.bound-mcp"); }
    }

    private McpServer server(String endpoint) {
        return new McpServer("test", "test", endpoint, null, true, Instant.now());
    }

    private String serve(Handler handler) throws IOException {
        remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        remote.createContext("/mcp", exchange -> {
            try {
                handler.handle(exchange);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        remote.start();
        return "http://127.0.0.1:" + remote.getAddress().getPort() + "/mcp";
    }

    private static void protocol(HttpExchange exchange, long toolDelayMs) throws IOException, InterruptedException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (request.contains("notifications/initialized")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if (request.contains("\"method\":\"tools/call\"")) Thread.sleep(toolDelayMs);
        String response = request.contains("\"method\":\"initialize\"")
                ? "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2025-06-18\"}}"
                : "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[],\"isError\":false}}";
        respond(exchange, 200, response);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }
}
