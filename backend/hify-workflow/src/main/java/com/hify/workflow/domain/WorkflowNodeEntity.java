package com.hify.workflow.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="workflow_nodes")
public class WorkflowNodeEntity {
 @Id private String id; private String workflowId; private String nodeKey; private String nodeType; private String name; @Column(columnDefinition="TEXT") private String configJson; private Instant createdAt;
 protected WorkflowNodeEntity(){} public WorkflowNodeEntity(String id,String workflowId,String nodeKey,String nodeType,String name,String configJson,Instant now){this.id=id;this.workflowId=workflowId;this.nodeKey=nodeKey;this.nodeType=nodeType;this.name=name;this.configJson=configJson;this.createdAt=now;}
 public String getId(){return id;} public String getWorkflowId(){return workflowId;} public String getNodeKey(){return nodeKey;} public String getNodeType(){return nodeType;} public String getName(){return name;} public String getConfigJson(){return configJson;}
}
