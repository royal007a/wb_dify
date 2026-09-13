package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

@TableName("providers")
public class ProviderEntity extends BaseEntity {
    private String publicId;
    private String name;
    private String type;
    private String baseUrl;
    private String authConfig;
    private String defaultModelId;
    private Boolean enabled;

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthConfig() { return authConfig; }
    public void setAuthConfig(String authConfig) { this.authConfig = authConfig; }
    public String getDefaultModelId() { return defaultModelId; }
    public void setDefaultModelId(String defaultModelId) { this.defaultModelId = defaultModelId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
