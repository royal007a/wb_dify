package com.hify.provider.runtime;

import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderType;
import com.hify.runtime.ModelClient;

public interface ProviderProtocolAdapter {
    ProviderType type();
    ModelClient create(ProviderRuntimeConfig provider);
}
