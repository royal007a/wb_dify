package com.hify.workflow.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="workflow_node_runs")
public class WorkflowNodeRun {
 @Id private String id; private String workflowRunId; private int sequenceNo; private String nodeKey; private String nodeType; private String status; @Column(columnDefinition="TEXT") private String outputJson; private String errorMessage; private Long elapsedMs; private Instant createdAt; private Instant finishedAt;
 protected WorkflowNodeRun(){} public WorkflowNodeRun(String id,String runId,int sequence,String key,String type,Instant now){this.id=id;this.workflowRunId=runId;this.sequenceNo=sequence;this.nodeKey=key;this.nodeType=type;this.status="RUNNING";this.createdAt=now;}
 public void succeed(String output,long elapsed){this.status="SUCCEEDED";this.outputJson=output;this.elapsedMs=elapsed;this.finishedAt=Instant.now();}
 public void fail(String error,long elapsed){this.status="FAILED";this.errorMessage=error;this.elapsedMs=elapsed;this.finishedAt=Instant.now();}
 public int getSequenceNo(){return sequenceNo;} public String getNodeKey(){return nodeKey;} public String getNodeType(){return nodeType;} public String getStatus(){return status;} public String getOutputJson(){return outputJson;} public String getErrorMessage(){return errorMessage;} public Long getElapsedMs(){return elapsedMs;}
}
