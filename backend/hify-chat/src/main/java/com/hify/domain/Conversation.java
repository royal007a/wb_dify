package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    private String id;
    private String agentId;
    private String agentVersionId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;

    protected Conversation() {}

    public Conversation(String id, String agentId, String title, Instant createdAt) {
        this(id, agentId, null, title, createdAt);
    }

    public Conversation(String id, String agentId, String agentVersionId, String title, Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.agentVersionId = agentVersionId;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void touch() { this.updatedAt = Instant.now(); }
    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public String getAgentVersionId() { return agentVersionId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
