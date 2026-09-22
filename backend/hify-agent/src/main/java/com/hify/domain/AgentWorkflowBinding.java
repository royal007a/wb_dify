package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "agent_workflow_bindings")
public class AgentWorkflowBinding {
    @Id private String agentId;
    private String workflowId;
    private Instant createdAt;
    protected AgentWorkflowBinding() {}
    public AgentWorkflowBinding(String agentId, String workflowId, Instant createdAt) { this.agentId=agentId; this.workflowId=workflowId; this.createdAt=createdAt; }
    public String getAgentId(){ return agentId; } public String getWorkflowId(){ return workflowId; }
}
