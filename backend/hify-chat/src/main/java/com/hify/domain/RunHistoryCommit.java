package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "run_history_commits", uniqueConstraints = {
        @UniqueConstraint(name = "uq_run_history_revision", columnNames = {"run_id", "revision"}),
        @UniqueConstraint(name = "uq_run_history_operation", columnNames = {"run_id", "operation_id"})
})
public class RunHistoryCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String runId;
    private long revision;
    private String operationId;
    private String semanticDigest;
    @Column(columnDefinition = "TEXT")
    private String messagesJson;
    private Instant committedAt;
    private Instant projectedAt;

    protected RunHistoryCommit() {}

    public RunHistoryCommit(String runId, long revision, String operationId,
                            String semanticDigest, String messagesJson, Instant committedAt) {
        this.runId = runId;
        this.revision = revision;
        this.operationId = operationId;
        this.semanticDigest = semanticDigest;
        this.messagesJson = messagesJson;
        this.committedAt = committedAt;
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public long getRevision() { return revision; }
    public String getOperationId() { return operationId; }
    public String getSemanticDigest() { return semanticDigest; }
    public String getMessagesJson() { return messagesJson; }
    public Instant getCommittedAt() { return committedAt; }
    public Instant getProjectedAt() { return projectedAt; }
    public void markProjected(Instant projectedAt) { this.projectedAt = projectedAt; }
}
