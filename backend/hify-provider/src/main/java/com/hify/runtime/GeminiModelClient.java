package com.hify.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmApiException;
import com.hify.common.LlmHttpClient;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.application.ProviderAuthConfig;
import com.hify.provider.application.ProviderAuthConfigCodec;
import com.hify.provider.runtime.CredentialResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GeminiModelClient implements ModelClient {
    private final ProviderRuntimeConfig provider;
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;
    private final ProviderAuthConfig auth;
    private final String credential;

    public GeminiModelClient(ProviderRuntimeConfig provider, ObjectMapper objectMapper,
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
        Map<String, String> toolNames = toolCallNames(request.messages());
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> system = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(RuntimeMessage::content).filter(value -> value != null && !value.isBlank()).toList();
        if (!system.isEmpty()) body.put("systemInstruction", Map.of("parts",
                List.of(Map.of("text", String.join("\n\n", system)))));
        body.put("contents", request.messages().stream()
                .filter(message -> !"system".equals(message.role()))
                .map(message -> toContent(message, toolNames)).toList());
        body.put("generationConfig", Map.of("temperature", request.temperature()));
        if (!request.tools().isEmpty()) body.put("tools", List.of(Map.of("functionDeclarations",
                request.tools().stream().map(this::toTool).toList())));

        JsonNode response;
        try {
            request.control().throwIfCancelled();
            String model = URLEncoder.encode(request.model(), StandardCharsets.UTF_8).replace("+", "%20");
            String url = stripTrailingSlash(provider.baseUrl()) + "/models/" + model + ":generateContent";
            String raw = resilience.execute(provider.id(), request.control(), () -> httpClient.post(
                    url, Map.of(auth.headerName(), auth.prefix() + credential),
                    writeJson(body), request.control()));
            response = objectMapper.readTree(raw);
        } catch (LlmApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Gemini returned invalid JSON", exception);
        }
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) throw new IllegalStateException("Gemini returned no candidates");
        List<RuntimeMessage.ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        parts.forEach(part -> {
            if (part.has("text")) text.append(part.path("text").asText());
            if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String id = call.path("id").asText();
                if (id.isBlank()) id = "gemini-" + UUID.randomUUID();
                try {
                    Map<String, Object> args = objectMapper.convertValue(call.path("args"), new TypeReference<>() {});
                    calls.add(new RuntimeMessage.ToolCall(id, call.path("name").asText(), args));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Invalid Gemini function call", exception);
                }
            }
        });
        return calls.isEmpty() ? RuntimeMessage.assistant(text.toString())
                : new RuntimeMessage("assistant", text.toString(), null, calls);
    }

    private Map<String, Object> toContent(RuntimeMessage message, Map<String, String> toolNames) {
        String role = "assistant".equals(message.role()) ? "model" : "user";
        List<Map<String, Object>> parts = new ArrayList<>();
        if ("tool".equals(message.role())) {
            String name = toolNames.get(message.toolCallId());
            if (name == null) throw new IllegalStateException("Gemini tool result has no matching call");
            parts.add(Map.of("functionResponse", Map.of("id", message.toolCallId(), "name", name,
                    "response", Map.of("result", message.content()))));
        } else {
            if (message.content() != null && !message.content().isBlank()) parts.add(Map.of("text", message.content()));
            if (message.toolCalls() != null) message.toolCalls().forEach(call -> parts.add(Map.of(
                    "functionCall", Map.of("id", call.id(), "name", call.name(), "args", call.arguments()))));
        }
        return Map.of("role", role, "parts", parts);
    }

    private Map<String, String> toolCallNames(List<RuntimeMessage> messages) {
        Map<String, String> result = new LinkedHashMap<>();
        messages.forEach(message -> {
            if (message.toolCalls() != null) message.toolCalls().forEach(call -> result.put(call.id(), call.name()));
        });
        return result;
    }

    private Map<String, Object> toTool(ToolDefinition tool) {
        return Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema());
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialize Gemini request", exception); }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
