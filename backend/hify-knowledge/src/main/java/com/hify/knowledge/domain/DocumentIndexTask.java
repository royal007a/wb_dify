package com.hify.knowledge.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "document_index_tasks")
public class DocumentIndexTask {
    @Id private String id;
    private String documentId;
    private int documentVersion;
    private String state;
    private int attempt;
    private String leaseOwner;
    private int checkpointOrdinal;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    protected DocumentIndexTask() {}
    public DocumentIndexTask(String id, String documentId, int version, Instant now) {
        this.id=id; this.documentId=documentId; this.documentVersion=version; this.state="PENDING";
        this.attempt=0; this.checkpointOrdinal=0; this.createdAt=now;
    }
    public void running(String owner) { state="RUNNING"; attempt++; leaseOwner=owner; startedAt=Instant.now(); }
    public void checkpoint(int ordinal) { checkpointOrdinal=ordinal; }
    public void succeeded() { state="SUCCEEDED"; finishedAt=Instant.now(); leaseOwner=null; }
    public void failed(String message) { state="FAILED"; errorMessage=message; finishedAt=Instant.now(); leaseOwner=null; }
    public String getId(){return id;} public String getDocumentId(){return documentId;} public int getDocumentVersion(){return documentVersion;}
    public String getState(){return state;} public int getAttempt(){return attempt;} public String getLeaseOwner(){return leaseOwner;}
    public int getCheckpointOrdinal(){return checkpointOrdinal;} public String getErrorMessage(){return errorMessage;}
    public Instant getCreatedAt(){return createdAt;} public Instant getStartedAt(){return startedAt;} public Instant getFinishedAt(){return finishedAt;}
}
