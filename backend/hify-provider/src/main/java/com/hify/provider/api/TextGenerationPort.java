package com.hify.provider.api;

import com.hify.common.ExecutionControl;

/** Single bounded text generation; no tools, no streaming delivery, no automatic verification. */
public interface TextGenerationPort {
    record Profile(ProviderRuntimeConfig provider, String modelId) {}
    Profile freeze(String providerId, String modelId);
    String generate(Profile profile, String system, String prompt, double temperature,
                    int maxOutputTokens, ExecutionControl control);
}
