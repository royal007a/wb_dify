package com.hify.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmHttpClient;
import com.hify.domain.ModelProvider;
import org.springframework.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleModelClient implements ModelClient {
    private final ModelProvider provider;
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;
    private final String apiKey;

    public OpenAiCompatibleModelClient(ModelProvider provider, ObjectMapper objectMapper,
                                       LlmHttpClient httpClient, CircuitBreakerService resilience) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.resilience = resilience;
        this.apiKey = System.getenv(provider.getApiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key environment variable: " + provider.getApiKeyEnv());
        }
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
            String raw = resilience.execute(provider.getId(), request.control(), () -> httpClient.post(
                    stripTrailingSlash(provider.getBaseUrl()) + "/chat/completions",
                    Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey),
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
