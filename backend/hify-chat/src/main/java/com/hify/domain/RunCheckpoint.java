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
@Table(name = "run_checkpoints", uniqueConstraints =
        @UniqueConstraint(name = "uq_run_checkpoint_sequence", columnNames = {"run_id", "sequence_no"}))
public class RunCheckpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String runId;
    @Column(name = "sequence_no")
    private long sequenceNo;
    private String checkpointId;
    private int turnNo;
    private int toolCalls;
    private String planId;
    private int planVersion;
    private String planDigest;
    @Column(columnDefinition = "TEXT")
    private String planJson;
    private long evidenceVersion;
    private long gapVersion;
    @Column(columnDefinition = "TEXT")
    private String contextJson;
    @Column(columnDefinition = "TEXT")
    private String messagesJson;
    private boolean restorable;
    private Instant createdAt;

    protected RunCheckpoint() {}

    public RunCheckpoint(String runId, long sequenceNo, String checkpointId, int turnNo,
                         int toolCalls, String planId, int planVersion, String planDigest,
                         String planJson, long evidenceVersion, long gapVersion,
                         String contextJson, String messagesJson,
                         boolean restorable, Instant createdAt) {
        this.runId = runId;
        this.sequenceNo = sequenceNo;
        this.checkpointId = checkpointId;
        this.turnNo = turnNo;
        this.toolCalls = toolCalls;
        this.planId = planId;
        this.planVersion = planVersion;
        this.planDigest = planDigest;
        this.planJson = planJson;
        this.evidenceVersion = evidenceVersion;
        this.gapVersion = gapVersion;
        this.contextJson = contextJson;
        this.messagesJson = messagesJson;
        this.restorable = restorable;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public long getSequenceNo() { return sequenceNo; }
    public String getCheckpointId() { return checkpointId; }
    public int getTurnNo() { return turnNo; }
    public int getToolCalls() { return toolCalls; }
    public String getPlanId() { return planId; }
    public int getPlanVersion() { return planVersion; }
    public String getPlanDigest() { return planDigest; }
    public String getPlanJson() { return planJson; }
    public long getEvidenceVersion() { return evidenceVersion; }
    public long getGapVersion() { return gapVersion; }
    public String getContextJson() { return contextJson; }
    public String getMessagesJson() { return messagesJson; }
    public boolean isRestorable() { return restorable; }
    public Instant getCreatedAt() { return createdAt; }
}
