package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_definitions")
public class AgentDefinition {
    @Id
    private String id;
    private String name;
    private String description;
    private String providerId;
    private String model;
    private double temperature;
    private int maxTurns;
    private boolean enabled;
    private int maxTokens;
    private int maxContextTurns;
    private int draftRevision;
    private String publishedVersionId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant archivedAt;
    @jakarta.persistence.Column(length = 8000)
    private String instructions;

    protected AgentDefinition() {}

    public AgentDefinition(String id, String name, String description, String instructions,
                           String providerId, String model, double temperature, int maxTurns,
                           boolean enabled) {
        this(id, name, description, instructions, providerId, model, temperature,
                2048, maxTurns, 10, enabled);
    }

    public AgentDefinition(String id, String name, String description, String instructions,
                           String providerId, String model, double temperature, int maxTokens,
                           int maxTurns, int maxContextTurns, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.providerId = providerId;
        this.model = model;
        this.temperature = temperature;
        this.maxTurns = maxTurns;
        this.enabled = enabled;
        this.maxTokens = maxTokens;
        this.maxContextTurns = maxContextTurns;
        this.draftRevision = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateDraft(String name, String description, String instructions,
                            String providerId, String model, double temperature,
                            int maxTokens, int maxTurns, int maxContextTurns,
                            boolean enabled) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.providerId = providerId;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.maxTurns = maxTurns;
        this.maxContextTurns = maxContextTurns;
        this.enabled = enabled;
        this.draftRevision++;
        this.updatedAt = Instant.now();
    }

    public void markPublished(String versionId) {
        this.publishedVersionId = versionId;
        this.updatedAt = Instant.now();
    }

    public void touchDraft() {
        this.draftRevision++;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant archivedAt) {
        this.enabled = false;
        this.archivedAt = archivedAt;
        this.draftRevision++;
        this.updatedAt = archivedAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public String getProviderId() { return providerId; }
    public String getModel() { return model; }
    public double getTemperature() { return temperature; }
    public int getMaxTurns() { return maxTurns; }
    public boolean isEnabled() { return enabled; }
    public int getMaxTokens() { return maxTokens; }
    public int getMaxContextTurns() { return maxContextTurns; }
    public int getDraftRevision() { return draftRevision; }
    public String getPublishedVersionId() { return publishedVersionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
