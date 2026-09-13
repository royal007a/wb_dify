package com.hify.provider.api;

public interface ProviderQueryService {
    ProviderRuntimeConfig requireEnabled(String publicId);
    String requireEnabledModel(String publicId, String modelId);
    boolean exists(String publicId);
}
