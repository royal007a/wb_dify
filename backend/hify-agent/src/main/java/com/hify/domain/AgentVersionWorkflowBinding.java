package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity @Table(name = "agent_version_workflow_bindings")
public class AgentVersionWorkflowBinding {
    @Id private String agentVersionId;
    private String workflowId;
    private String workflowVersionId;
    private String workflowChecksum;
    private Instant createdAt;
    protected AgentVersionWorkflowBinding() {}
    public AgentVersionWorkflowBinding(String agentVersionId,String workflowId,String workflowVersionId,String workflowChecksum,Instant createdAt){this.agentVersionId=agentVersionId;this.workflowId=workflowId;this.workflowVersionId=workflowVersionId;this.workflowChecksum=workflowChecksum;this.createdAt=createdAt;}
    public String getAgentVersionId(){return agentVersionId;} public String getWorkflowId(){return workflowId;} public String getWorkflowVersionId(){return workflowVersionId;} public String getWorkflowChecksum(){return workflowChecksum;}
}
