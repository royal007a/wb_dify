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
@Table(name = "agent_version_tool_bindings")
@IdClass(AgentVersionToolBinding.Key.class)
public class AgentVersionToolBinding {
    @Id
    @Column(name = "agent_version_id")
    private String agentVersionId;

    @Id
    @Column(name = "tool_name")
    private String toolName;

    private Instant createdAt;

    protected AgentVersionToolBinding() {}

    public AgentVersionToolBinding(String agentVersionId, String toolName, Instant createdAt) {
        this.agentVersionId = agentVersionId;
        this.toolName = toolName;
        this.createdAt = createdAt;
    }

    public String getAgentVersionId() { return agentVersionId; }
    public String getToolName() { return toolName; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Key implements Serializable {
        private String agentVersionId;
        private String toolName;

        public Key() {}

        public Key(String agentVersionId, String toolName) {
            this.agentVersionId = agentVersionId;
            this.toolName = toolName;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key key)) return false;
            return Objects.equals(agentVersionId, key.agentVersionId)
                    && Objects.equals(toolName, key.toolName);
        }

        @Override
        public int hashCode() { return Objects.hash(agentVersionId, toolName); }
    }
}
