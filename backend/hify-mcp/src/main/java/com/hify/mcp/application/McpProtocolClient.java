package com.hify.mcp.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import com.hify.mcp.domain.McpServer;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class McpProtocolClient {
    private static final String PROTOCOL = "2025-06-18";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONTROL_POLL = Duration.ofMillis(100);

    private final ObjectMapper json;
    private final McpEndpointGuard guard;
    private final McpCredentialResolver credentials;
    private final HttpClient http;

    public McpProtocolClient(ObjectMapper json, McpEndpointGuard guard, McpCredentialResolver credentials) {
        this.json = json;
        this.guard = guard;
        this.credentials = credentials;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public List<RemoteTool> listTools(McpServer server) {
        Session session = initialize(server, ExecutionControl.none());
        JsonNode result = exchange(server, session, "tools/list", json.createObjectNode(),
                ExecutionControl.none()).path("result");
        if (!result.path("tools").isArray()) {
            throw new BizException(ErrorCode.CONFLICT, "MCP tools/list returned no tools array");
        }
        List<RemoteTool> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            String name = tool.path("name").asText();
            if (name.isBlank()) throw new BizException(ErrorCode.CONFLICT, "MCP tool name is missing");
            JsonNode schema = tool.path("inputSchema");
            if (!schema.isObject()) {
                throw new BizException(ErrorCode.CONFLICT, "MCP tool inputSchema is missing");
            }
            boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
            tools.add(new RemoteTool(name, tool.path("description").asText(""), schema,
                    readOnly ? "READ" : "EXTERNAL"));
        }
        return tools;
    }

    public CallResult call(McpServer server, String toolName, JsonNode arguments) {
        return call(server, toolName, arguments, ExecutionControl.none());
    }

    public CallResult call(McpServer server, String toolName, JsonNode arguments, ExecutionControl control) {
        Session session = initialize(server, control);
        ObjectNode params = json.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments);
        JsonNode result = exchange(server, session, "tools/call", params, control).path("result");
        return new CallResult(result, result.path("isError").asBoolean(false));
    }

    private Session initialize(McpServer server, ExecutionControl control) {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL);
        params.set("capabilities", json.createObjectNode());
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "hify");
        info.put("version", "0.1.0");
        Exchange init = post(server, null, "initialize", params, control);
        String protocol = init.body().path("result").path("protocolVersion").asText();
        if (protocol.isBlank()) {
            throw new BizException(ErrorCode.CONFLICT, "MCP initialize response is invalid");
        }
        Session session = new Session(init.sessionId());
        notifyInitialized(server, session, control);
        return session;
    }

    private JsonNode exchange(McpServer server, Session session, String method, JsonNode params,
                              ExecutionControl control) {
        return post(server, session, method, params, control).body();
    }

    private Exchange post(McpServer server, Session session, String method, JsonNode params,
                          ExecutionControl control) {
        try {
            URI uri = guard.validate(server.getEndpointUrl());
            String secret = credentials.resolve(server.getId(), server.getCredentialRef());
            ObjectNode rpc = json.createObjectNode();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", UUID.randomUUID().toString());
            rpc.put("method", method);
            rpc.set("params", params);
            HttpRequest request = request(secret, session, uri)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(rpc), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = await(http.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), control);
            if (response.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.CONFLICT, "MCP server returned HTTP " + response.statusCode());
            }
            JsonNode body = parse(response, secret);
            if (body.has("error")) {
                throw new BizException(ErrorCode.CONFLICT,
                        "MCP error: " + body.path("error").path("message").asText("unknown"));
            }
            return new Exchange(body, response.headers().firstValue("mcp-session-id").orElse(null));
        } catch (BizException | ExecutionCancelledException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.CONFLICT, "MCP request failed (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private HttpRequest.Builder request(String secret, Session session, URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL);
        if (session != null && session.id() != null) request.header("Mcp-Session-Id", session.id());
        if (secret != null) request.header("Authorization", "Bearer " + secret);
        return request;
    }

    private void notifyInitialized(McpServer server, Session session, ExecutionControl control) {
        try {
            URI uri = guard.validate(server.getEndpointUrl());
            String secret = credentials.resolve(server.getId(), server.getCredentialRef());
            ObjectNode rpc = json.createObjectNode();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", "notifications/initialized");
            HttpRequest request = request(secret, session, uri)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(rpc), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = await(http.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), control);
            if (response.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.CONFLICT, "MCP initialized notification failed");
            }
        } catch (BizException | ExecutionCancelledException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BizException(ErrorCode.CONFLICT,
                    "MCP initialized notification failed (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private <T> T await(CompletableFuture<T> future, ExecutionControl control) {
        try {
            while (true) {
                control.throwIfCancelled();
                if (control.isExpired()) {
                    future.cancel(true);
                    throw new BizException(ErrorCode.CONFLICT, "MCP request timed out");
                }
                long waitNanos = Math.max(1L, control.remaining(CONTROL_POLL).toNanos());
                try {
                    return future.get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                    // Poll the shared Run cancellation/deadline token.
                }
            }
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException("MCP request interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new BizException(ErrorCode.CONFLICT, "MCP transport failed");
        }
    }

    private JsonNode parse(HttpResponse<String> response, String secret) throws Exception {
        String type = response.headers().firstValue("content-type").orElse("");
        String raw = response.body();
        // An untrusted endpoint can echo Authorization in tool output or error text.
        if (secret != null) raw = raw.replace(secret, "[REDACTED]");
        if (type.contains("text/event-stream")) {
            String last = null;
            for (String line : raw.split("\\R")) {
                if (line.startsWith("data:")) last = line.substring(5).trim();
            }
            if (last == null) throw new BizException(ErrorCode.CONFLICT, "MCP SSE response has no data event");
            return redact(json.readTree(last), secret);
        }
        return redact(json.readTree(raw), secret);
    }

    private JsonNode redact(JsonNode node, String secret) {
        if (node == null || secret == null) return node;
        if (node.isTextual()) return json.getNodeFactory().textNode(node.asText().replace(secret, "[REDACTED]"));
        if (node.isObject()) {
            ObjectNode clean = json.createObjectNode();
            node.fields().forEachRemaining(entry -> clean.set(entry.getKey().replace(secret, "[REDACTED]"), redact(entry.getValue(), secret)));
            return clean;
        }
        if (node.isArray()) {
            var clean = json.createArrayNode();
            node.forEach(value -> clean.add(redact(value, secret)));
            return clean;
        }
        return node;
    }

    public record RemoteTool(String name, String description, JsonNode inputSchema, String risk) {}
    public record CallResult(JsonNode result, boolean error) {}
    private record Session(String id) {}
    private record Exchange(JsonNode body, String sessionId) {}
}
