package com.hify.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmHttpClient;
import com.hify.domain.ModelProvider;
import com.hify.domain.ProviderType;
import org.springframework.stereotype.Component;

@Component
public class ModelClientFactory {
    private final ObjectMapper objectMapper;
    private final LlmHttpClient httpClient;
    private final CircuitBreakerService resilience;

    public ModelClientFactory(ObjectMapper objectMapper, LlmHttpClient httpClient,
                              CircuitBreakerService resilience) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.resilience = resilience;
    }

    public ModelClient create(ModelProvider provider) {
        if (!provider.isEnabled()) {
            throw new IllegalStateException("Model provider is disabled: " + provider.getId());
        }
        if (provider.getType() == ProviderType.MOCK) {
            return new MockModelClient();
        }
        return new OpenAiCompatibleModelClient(provider, objectMapper, httpClient, resilience);
    }
}
