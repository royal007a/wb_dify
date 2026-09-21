package com.hify.workflow.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="workflow_versions")
public class WorkflowVersion {
 @Id private String id; private String workflowId; private int versionNo; private int schemaVersion; @Column(columnDefinition="TEXT") private String dslJson; private String checksum; private Instant createdAt;
 protected WorkflowVersion(){} public WorkflowVersion(String id,String workflowId,int versionNo,int schemaVersion,String dslJson,String checksum,Instant now){this.id=id;this.workflowId=workflowId;this.versionNo=versionNo;this.schemaVersion=schemaVersion;this.dslJson=dslJson;this.checksum=checksum;this.createdAt=now;}
 public String getId(){return id;} public String getWorkflowId(){return workflowId;} public int getVersionNo(){return versionNo;} public int getSchemaVersion(){return schemaVersion;} public String getDslJson(){return dslJson;} public String getChecksum(){return checksum;} public Instant getCreatedAt(){return createdAt;}
}
