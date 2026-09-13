package com.hify.provider.api;

public interface ProviderQueryService {
    ProviderRuntimeConfig requireEnabled(String publicId);
    boolean exists(String publicId);
}
