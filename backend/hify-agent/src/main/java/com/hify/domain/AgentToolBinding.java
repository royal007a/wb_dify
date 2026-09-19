package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_tool_bindings")
@IdClass(AgentToolBinding.Key.class)
public class AgentToolBinding {
    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Id
    @Column(name = "tool_name")
    private String toolName;

    private Instant createdAt;

    protected AgentToolBinding() {}

    public AgentToolBinding(String agentId, String toolName, Instant createdAt) {
        this.agentId = agentId;
        this.toolName = toolName;
        this.createdAt = createdAt;
    }

    public String getAgentId() { return agentId; }
    public String getToolName() { return toolName; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Key implements Serializable {
        private String agentId;
        private String toolName;

        public Key() {}

        public Key(String agentId, String toolName) {
            this.agentId = agentId;
            this.toolName = toolName;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key key)) return false;
            return Objects.equals(agentId, key.agentId) && Objects.equals(toolName, key.toolName);
        }

        @Override
        public int hashCode() { return Objects.hash(agentId, toolName); }
    }
}
