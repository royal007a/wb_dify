package com.hify.provider.api;

public enum ProviderType {
    OPENAI("https://api.openai.com/v1", true),
    ANTHROPIC("https://api.anthropic.com", true),
    GEMINI("https://generativelanguage.googleapis.com/v1beta", true),
    OPENAI_COMPATIBLE(null, true),
    MOCK("mock://local", false);

    private final String defaultBaseUrl;
    private final boolean manageable;

    ProviderType(String defaultBaseUrl, boolean manageable) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.manageable = manageable;
    }

    public String defaultBaseUrl() { return defaultBaseUrl; }
    public boolean manageable() { return manageable; }
}
