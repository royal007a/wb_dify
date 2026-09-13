package com.hify.runtime;

import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.runtime.ProviderAdapterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ModelClientFactory {
    private final ProviderAdapterRegistry adapters;

    public ModelClientFactory(ProviderAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    public ModelClient create(ProviderRuntimeConfig provider) {
        if (!provider.enabled()) {
            throw new IllegalStateException("Model provider is disabled: " + provider.id());
        }
        return adapters.create(provider);
    }
}
