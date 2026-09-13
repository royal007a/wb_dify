package com.hify.provider.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.provider.api.ProviderAuthInput;
import com.hify.provider.api.ProviderType;
import org.springframework.stereotype.Component;

@Component
public class ProviderAuthConfigCodec {
    private final ObjectMapper objectMapper;

    public ProviderAuthConfigCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ProviderType type, ProviderAuthInput input) {
        String header = input.headerName();
        String prefix = input.prefix();
        if (header == null || header.isBlank()) {
            header = switch (type) {
                case ANTHROPIC -> "x-api-key";
                case GEMINI -> "x-goog-api-key";
                default -> "Authorization";
            };
        }
        if (prefix == null) prefix = type == ProviderType.OPENAI
                || type == ProviderType.OPENAI_COMPATIBLE ? "Bearer " : "";
        return write(new ProviderAuthConfig(1, normalizeRef(input.credentialRef()), header, prefix));
    }

    public ProviderAuthConfig decode(String value) {
        try {
            ProviderAuthConfig config = objectMapper.readValue(value, ProviderAuthConfig.class);
            if (config.version() != 1) throw new IllegalArgumentException("Unsupported auth config version");
            return config;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Provider auth config is invalid", exception);
        }
    }

    public boolean configured(String value) {
        try {
            String ref = decode(value).credentialRef();
            return ref != null && !ref.isBlank();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String write(ProviderAuthConfig value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Provider auth config", exception);
        }
    }

    private String normalizeRef(String value) {
        String ref = value.trim();
        return ref.contains(":") ? ref : "env:" + ref;
    }
}
