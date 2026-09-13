package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
    private String enabledTools;
    private boolean enabled;
    @jakarta.persistence.Column(length = 8000)
    private String instructions;

    protected AgentDefinition() {}

    public AgentDefinition(String id, String name, String description, String instructions,
                           String providerId, String model, double temperature, int maxTurns,
                           String enabledTools, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.providerId = providerId;
        this.model = model;
        this.temperature = temperature;
        this.maxTurns = maxTurns;
        this.enabledTools = enabledTools;
        this.enabled = enabled;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInstructions() { return instructions; }
    public String getProviderId() { return providerId; }
    public String getModel() { return model; }
    public double getTemperature() { return temperature; }
    public int getMaxTurns() { return maxTurns; }
    public String getEnabledTools() { return enabledTools; }
    public boolean isEnabled() { return enabled; }
}

