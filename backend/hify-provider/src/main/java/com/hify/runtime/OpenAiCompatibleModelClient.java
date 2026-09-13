package com.hify.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmHttpClient;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.application.ProviderAuthConfig;
import com.hify.provider.application.ProviderAuthConfigCodec;
import com.hify.provider.runtime.CredentialResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OpenAiCompatibleModelClient implements ModelClient {
    private final ProviderRuntimeConfig provider;
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;
    private final String credential;
    private final ProviderAuthConfig auth;

    public OpenAiCompatibleModelClient(ProviderRuntimeConfig provider, ObjectMapper objectMapper,
                                       LlmHttpClient httpClient, CircuitBreakerService resilience,
                                       ProviderAuthConfigCodec authCodec, CredentialResolver credentials) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.resilience = resilience;
        this.auth = authCodec.decode(provider.authConfig());
        this.credential = credentials.resolve(auth.credentialRef());
    }

    @Override
    public RuntimeMessage generate(ModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("messages", request.messages().stream().map(this::toMessage).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toTool).toList());
        }

        JsonNode response;
        try {
            request.control().throwIfCancelled();
            String raw = resilience.execute(provider.id(), request.control(), () -> httpClient.post(
                    stripTrailingSlash(provider.baseUrl()) + "/chat/completions",
                    Map.of(auth.headerName(), auth.prefix() + credential),
                    writeJson(body), request.control()));
            response = objectMapper.readTree(raw);
        } catch (com.hify.common.LlmApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Model provider returned invalid JSON", exception);
        }
        if (response == null || response.path("choices").isEmpty()) {
            throw new IllegalStateException("Model provider returned no choices");
        }
        JsonNode message = response.path("choices").get(0).path("message");
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            List<RuntimeMessage.ToolCall> calls = new ArrayList<>();
            toolCalls.forEach(node -> calls.add(parseToolCall(node)));
            return new RuntimeMessage("assistant", nullableText(message.get("content")), null, calls);
        }
        return RuntimeMessage.assistant(nullableText(message.get("content")));
    }

    @Override
    public RuntimeMessage generateStream(ModelRequest request, ModelStreamObserver observer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("stream", true);
        body.put("messages", request.messages().stream().map(this::toMessage).toList());
        if (!request.tools().isEmpty()) body.put("tools", request.tools().stream().map(this::toTool).toList());

        StringBuilder text = new StringBuilder();
        Map<Integer, PartialToolCall> partialCalls = new TreeMap<>();
        httpClient.streamAndAwait(stripTrailingSlash(provider.baseUrl()) + "/chat/completions",
                Map.of(auth.headerName(), auth.prefix() + credential), writeJson(body), request.control(),
                new LlmHttpClient.StreamCallback() {
                    @Override public void onEvent(String id, String type, String data) {
                        if ("[DONE]".equals(data)) return;
                        try {
                            JsonNode delta = objectMapper.readTree(data).path("choices").path(0).path("delta");
                            String chunk = nullableText(delta.get("content"));
                            if (!chunk.isEmpty()) { text.append(chunk); observer.onTextDelta(chunk); }
                            JsonNode tools = delta.path("tool_calls");
                            if (tools.isArray()) tools.forEach(node -> {
                                int index = node.path("index").asInt(0);
                                PartialToolCall call = partialCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
                                if (node.hasNonNull("id")) call.id.append(node.path("id").asText());
                                JsonNode function = node.path("function");
                                if (function.hasNonNull("name")) call.name.append(function.path("name").asText());
                                if (function.hasNonNull("arguments")) call.arguments.append(function.path("arguments").asText());
                            });
                        } catch (Exception exception) {
                            throw new IllegalStateException("OpenAI stream returned invalid JSON", exception);
                        }
                    }
                    @Override public void onClosed() {}
                    @Override public void onFailure(com.hify.common.LlmApiException exception) {}
                });
        List<RuntimeMessage.ToolCall> calls = partialCalls.values().stream().map(this::parsePartial).toList();
        return new RuntimeMessage("assistant", text.toString(), null, calls);
    }

    private RuntimeMessage.ToolCall parsePartial(PartialToolCall call) {
        try {
            Map<String, Object> arguments = objectMapper.readValue(
                    call.arguments.isEmpty() ? "{}" : call.arguments.toString(), new TypeReference<>() {});
            return new RuntimeMessage.ToolCall(call.id.toString(), call.name.toString(), arguments);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid streamed tool call arguments", exception);
        }
    }

    private static final class PartialToolCall {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private Map<String, Object> toMessage(RuntimeMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", message.role());
        if (message.content() != null) result.put("content", message.content());
        if (message.toolCallId() != null) result.put("tool_call_id", message.toolCallId());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            result.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(),
                    "type", "function",
                    "function", Map.of(
                            "name", call.name(),
                            "arguments", writeJson(call.arguments())
                    )
            )).toList());
        }
        return result;
    }

    private Map<String, Object> toTool(ToolDefinition tool) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.inputSchema()
                )
        );
    }

    private RuntimeMessage.ToolCall parseToolCall(JsonNode node) {
        try {
            String arguments = node.path("function").path("arguments").asText("{}");
            Map<String, Object> parsed = objectMapper.readValue(arguments, new TypeReference<>() {});
            return new RuntimeMessage.ToolCall(
                    node.path("id").asText(), node.path("function").path("name").asText(), parsed);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid tool call arguments from model", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize model request", exception);
        }
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
