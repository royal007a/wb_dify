package com.hify.provider.runtime;

import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderType;
import com.hify.runtime.MockModelClient;
import com.hify.runtime.ModelClient;
import org.springframework.stereotype.Component;

@Component
public class MockProtocolAdapter implements ProviderProtocolAdapter {
    @Override public ProviderType type() { return ProviderType.MOCK; }
    @Override public ModelClient create(ProviderRuntimeConfig provider) { return new MockModelClient(); }
}
