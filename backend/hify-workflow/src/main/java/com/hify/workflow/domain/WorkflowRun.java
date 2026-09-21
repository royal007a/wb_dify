package com.hify.workflow.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="workflow_runs")
public class WorkflowRun {
 @Id private String id; private String workflowVersionId; private String workflowDigest; private String status; @Column(columnDefinition="TEXT") private String inputText; @Column(columnDefinition="TEXT") private String outputText; @Column(columnDefinition="TEXT") private String contextJson; private String errorMessage; private Long elapsedMs; private Instant createdAt; private Instant finishedAt; @Version private long rowVersion;
 protected WorkflowRun(){} public WorkflowRun(String id,String versionId,String digest,String input,Instant now){this.id=id;this.workflowVersionId=versionId;this.workflowDigest=digest;this.status="RUNNING";this.inputText=input;this.contextJson="{}";this.createdAt=now;}
 public void succeed(String output,String context,long elapsed){this.status="SUCCEEDED";this.outputText=output;this.contextJson=context;this.elapsedMs=elapsed;this.finishedAt=Instant.now();}
 public void fail(String error,String context,long elapsed){this.status="FAILED";this.errorMessage=error;this.contextJson=context;this.elapsedMs=elapsed;this.finishedAt=Instant.now();}
 public String getId(){return id;} public String getWorkflowVersionId(){return workflowVersionId;} public String getWorkflowDigest(){return workflowDigest;} public String getStatus(){return status;} public String getInputText(){return inputText;} public String getOutputText(){return outputText;} public String getContextJson(){return contextJson;} public String getErrorMessage(){return errorMessage;} public Long getElapsedMs(){return elapsedMs;} public Instant getCreatedAt(){return createdAt;} public Instant getFinishedAt(){return finishedAt;}
}
