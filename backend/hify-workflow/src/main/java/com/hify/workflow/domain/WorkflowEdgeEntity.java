package com.hify.workflow.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="workflow_edges")
public class WorkflowEdgeEntity {
 @Id private String id; private String workflowId; private String edgeKey; private String sourceNodeKey; private String targetNodeKey; private String conditionValue; private boolean defaultBranch; private Instant createdAt;
 protected WorkflowEdgeEntity(){} public WorkflowEdgeEntity(String id,String workflowId,String edgeKey,String source,String target,String condition,boolean defaultBranch,Instant now){this.id=id;this.workflowId=workflowId;this.edgeKey=edgeKey;this.sourceNodeKey=source;this.targetNodeKey=target;this.conditionValue=condition;this.defaultBranch=defaultBranch;this.createdAt=now;}
 public String getId(){return id;} public String getWorkflowId(){return workflowId;} public String getEdgeKey(){return edgeKey;} public String getSourceNodeKey(){return sourceNodeKey;} public String getTargetNodeKey(){return targetNodeKey;} public String getConditionValue(){return conditionValue;} public boolean isDefaultBranch(){return defaultBranch;}
}
