package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "model_providers")
public class ModelProvider {
    @Id
    private String id;
    private String name;
    @Enumerated(EnumType.STRING)
    private ProviderType type;
    private String baseUrl;
    private String apiKeyEnv;
    private String defaultModel;
    private boolean enabled;

    protected ModelProvider() {}

    public ModelProvider(String id, String name, ProviderType type, String baseUrl,
                         String apiKeyEnv, String defaultModel, boolean enabled) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.baseUrl = baseUrl;
        this.apiKeyEnv = apiKeyEnv;
        this.defaultModel = defaultModel;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public ProviderType getType() { return type; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyEnv() { return apiKeyEnv; }
    public String getDefaultModel() { return defaultModel; }
    public boolean isEnabled() { return enabled; }
}

