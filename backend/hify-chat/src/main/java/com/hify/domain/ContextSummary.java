package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "context_summaries")
public class ContextSummary {
    @Id private String id;
    private String runId;
    private String conversationId;
    @Enumerated(EnumType.STRING) private ContextSummaryKind kind;
    private int rangeStart;
    private int rangeEnd;
    @Column(columnDefinition = "TEXT") private String contentJson;
    @Column(columnDefinition = "TEXT") private String sourceRefsJson;
    @Column(columnDefinition = "TEXT") private String criticalFactsJson;
    @Column(columnDefinition = "TEXT") private String constraintsJson;
    @Column(columnDefinition = "TEXT") private String decisionsJson;
    @Column(columnDefinition = "TEXT") private String openGapsJson;
    private String digest;
    private int summaryVersion;
    @Enumerated(EnumType.STRING) private ContextSummaryStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    @Version @Column(name = "row_version") private long rowVersion;

    protected ContextSummary() {}

    public ContextSummary(String id, String runId, String conversationId, ContextSummaryKind kind,
                          int rangeStart, int rangeEnd, String contentJson, String sourceRefsJson,
                          String criticalFactsJson, String constraintsJson, String decisionsJson,
                          String openGapsJson, String digest, int summaryVersion, Instant now) {
        this.id = id; this.runId = runId; this.conversationId = conversationId; this.kind = kind;
        this.rangeStart = rangeStart; this.rangeEnd = rangeEnd; this.contentJson = contentJson;
        this.sourceRefsJson = sourceRefsJson; this.criticalFactsJson = criticalFactsJson;
        this.constraintsJson = constraintsJson; this.decisionsJson = decisionsJson;
        this.openGapsJson = openGapsJson; this.digest = digest; this.summaryVersion = summaryVersion;
        this.status = ContextSummaryStatus.CURRENT; this.createdAt = now; this.updatedAt = now;
    }

    public void markStatus(ContextSummaryStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getConversationId() { return conversationId; }
    public ContextSummaryKind getKind() { return kind; }
    public int getRangeStart() { return rangeStart; }
    public int getRangeEnd() { return rangeEnd; }
    public String getContentJson() { return contentJson; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public String getCriticalFactsJson() { return criticalFactsJson; }
    public String getConstraintsJson() { return constraintsJson; }
    public String getDecisionsJson() { return decisionsJson; }
    public String getOpenGapsJson() { return openGapsJson; }
    public String getDigest() { return digest; }
    public int getSummaryVersion() { return summaryVersion; }
    public ContextSummaryStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getRowVersion() { return rowVersion; }
}
