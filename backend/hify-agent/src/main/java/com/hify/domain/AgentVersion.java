package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_versions")
public class AgentVersion {
    @Id
    private String id;
    private String agentId;
    private int versionNo;
    private String name;
    private String description;
    private String providerId;
    private String model;
    private double temperature;
    private int maxTokens;
    private int maxTurns;
    private int maxContextTurns;
    private boolean enabled;
    @jakarta.persistence.Column(length = 8000)
    private String instructions;
    private String snapshotDigest;
    private Instant createdAt;

    protected AgentVersion() {}

    public AgentVersion(String id, String agentId, int versionNo, AgentDefinition draft,
                        String digest, Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.versionNo = versionNo;
        this.name = draft.getName();
        this.description = draft.getDescription();
        this.instructions = draft.getInstructions();
        this.providerId = draft.getProviderId();
        this.model = draft.getModel();
        this.temperature = draft.getTemperature();
        this.maxTokens = draft.getMaxTokens();
        this.maxTurns = draft.getMaxTurns();
        this.maxContextTurns = draft.getMaxContextTurns();
        this.enabled = draft.isEnabled();
        this.snapshotDigest = digest;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public int getVersionNo() { return versionNo; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getProviderId() { return providerId; }
    public String getModel() { return model; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public int getMaxTurns() { return maxTurns; }
    public int getMaxContextTurns() { return maxContextTurns; }
    public boolean isEnabled() { return enabled; }
    public String getInstructions() { return instructions; }
    public String getSnapshotDigest() { return snapshotDigest; }
    public Instant getCreatedAt() { return createdAt; }
}
