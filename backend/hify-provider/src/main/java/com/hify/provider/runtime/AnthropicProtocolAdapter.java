package com.hify.provider.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmHttpClient;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderType;
import com.hify.provider.application.ProviderAuthConfigCodec;
import com.hify.runtime.AnthropicModelClient;
import com.hify.runtime.ModelClient;
import org.springframework.stereotype.Component;

@Component
public class AnthropicProtocolAdapter implements ProviderProtocolAdapter {
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;
    private final ProviderAuthConfigCodec authCodec;
    private final CredentialResolver credentials;

    public AnthropicProtocolAdapter(ObjectMapper objectMapper, LlmHttpClient httpClient,
                                    CircuitBreakerService resilience, ProviderAuthConfigCodec authCodec,
                                    CredentialResolver credentials) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.resilience = resilience;
        this.authCodec = authCodec;
        this.credentials = credentials;
    }

    @Override public ProviderType type() { return ProviderType.ANTHROPIC; }

    @Override public ModelClient create(ProviderRuntimeConfig provider) {
        return new AnthropicModelClient(provider, objectMapper, httpClient, resilience, authCodec, credentials);
    }
}
