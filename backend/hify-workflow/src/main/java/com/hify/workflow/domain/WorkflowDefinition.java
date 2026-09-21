package com.hify.workflow.domain;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="workflows")
public class WorkflowDefinition {
 @Id private String id; private String name; private String description; private int schemaVersion; private long draftRevision;
 private String publishedVersionId; private Instant archivedAt; private Instant createdAt; private Instant updatedAt; @Version private long rowVersion;
 protected WorkflowDefinition(){}
 public WorkflowDefinition(String id,String name,String description,int schemaVersion,Instant now){this.id=id;this.name=name;this.description=description;this.schemaVersion=schemaVersion;this.draftRevision=1;this.createdAt=now;this.updatedAt=now;}
 public void update(String name,String description,int schemaVersion){this.name=name;this.description=description;this.schemaVersion=schemaVersion;this.draftRevision++;this.updatedAt=Instant.now();}
 public void published(String versionId){this.publishedVersionId=versionId;this.updatedAt=Instant.now();}
 public void archive(){this.archivedAt=Instant.now();this.updatedAt=this.archivedAt;}
 public String getId(){return id;} public String getName(){return name;} public String getDescription(){return description;} public int getSchemaVersion(){return schemaVersion;}
 public long getDraftRevision(){return draftRevision;} public String getPublishedVersionId(){return publishedVersionId;} public Instant getArchivedAt(){return archivedAt;}
 public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
