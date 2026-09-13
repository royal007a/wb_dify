package com.hify.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmApiException;
import com.hify.common.LlmHttpClient;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.application.ProviderAuthConfig;
import com.hify.provider.application.ProviderAuthConfigCodec;
import com.hify.provider.runtime.CredentialResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnthropicModelClient implements ModelClient {
    private final ProviderRuntimeConfig provider;
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;
    private final ProviderAuthConfig auth;
    private final String credential;

    public AnthropicModelClient(ProviderRuntimeConfig provider, ObjectMapper objectMapper,
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
        body.put("max_tokens", 1024);
        body.put("temperature", request.temperature());
        List<String> system = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(RuntimeMessage::content).filter(value -> value != null && !value.isBlank()).toList();
        if (!system.isEmpty()) body.put("system", String.join("\n\n", system));
        body.put("messages", request.messages().stream()
                .filter(message -> !"system".equals(message.role()))
                .map(this::toMessage).toList());
        if (!request.tools().isEmpty()) body.put("tools", request.tools().stream().map(this::toTool).toList());

        JsonNode response;
        try {
            request.control().throwIfCancelled();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(auth.headerName(), auth.prefix() + credential);
            headers.put("anthropic-version", "2023-06-01");
            String raw = resilience.execute(provider.id(), request.control(), () -> httpClient.post(
                    stripTrailingSlash(provider.baseUrl()) + "/v1/messages", headers,
                    writeJson(body), request.control()));
            response = objectMapper.readTree(raw);
        } catch (LlmApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Anthropic returned invalid JSON", exception);
        }
        JsonNode content = response.path("content");
        if (!content.isArray()) throw new IllegalStateException("Anthropic returned no content");
        List<RuntimeMessage.ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        content.forEach(block -> {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
            if ("tool_use".equals(block.path("type").asText())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> input = objectMapper.convertValue(block.path("input"), Map.class);
                calls.add(new RuntimeMessage.ToolCall(block.path("id").asText(),
                        block.path("name").asText(), input));
            }
        });
        return calls.isEmpty() ? RuntimeMessage.assistant(text.toString())
                : new RuntimeMessage("assistant", text.toString(), null, calls);
    }

    private Map<String, Object> toMessage(RuntimeMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ("tool".equals(message.role())) {
            result.put("role", "user");
            result.put("content", List.of(Map.of("type", "tool_result",
                    "tool_use_id", message.toolCallId(), "content", message.content())));
            return result;
        }
        result.put("role", message.role());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (message.content() != null && !message.content().isBlank()) {
                blocks.add(Map.of("type", "text", "text", message.content()));
            }
            message.toolCalls().forEach(call -> blocks.add(Map.of("type", "tool_use", "id", call.id(),
                    "name", call.name(), "input", call.arguments())));
            result.put("content", blocks);
        } else {
            result.put("content", message.content() == null ? "" : message.content());
        }
        return result;
    }

    private Map<String, Object> toTool(ToolDefinition tool) {
        return Map.of("name", tool.name(), "description", tool.description(),
                "input_schema", tool.inputSchema());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialize Anthropic request", exception); }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
