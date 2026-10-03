package com.hify.mcp.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "mcp_credentials")
public class McpCredential {
    @Id private String id;
    @Column(nullable = false, updatable = false) private String serverId;
    @Column(nullable = false, updatable = false, columnDefinition = "text") private String encryptedValue;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    protected McpCredential() {}
    public McpCredential(String id, String serverId, String encryptedValue, Instant createdAt) {
        this.id = id;
        this.serverId = serverId;
        this.encryptedValue = encryptedValue;
        this.createdAt = createdAt;
    }
    public String getId() { return id; }
    public String getServerId() { return serverId; }
    public String getEncryptedValue() { return encryptedValue; }
}
