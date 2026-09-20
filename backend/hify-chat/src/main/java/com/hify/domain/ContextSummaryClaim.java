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
@Table(name = "context_summary_claims")
public class ContextSummaryClaim {
    @Id private String id;
    private String summaryId;
    private String claimKey;
    private String claimType;
    @Column(length = 4000) private String statement;
    @Column(columnDefinition = "TEXT") private String sourceRefsJson;
    @Enumerated(EnumType.STRING) private SummaryClaimStatus status;
    private String conflictGroup;
    private Instant createdAt;
    private Instant updatedAt;
    @Version @Column(name = "row_version") private long rowVersion;

    protected ContextSummaryClaim() {}

    public ContextSummaryClaim(String id, String summaryId, String claimKey, String claimType,
                               String statement, String sourceRefsJson, SummaryClaimStatus status,
                               Instant now) {
        this.id = id; this.summaryId = summaryId; this.claimKey = claimKey;
        this.claimType = claimType; this.statement = statement; this.sourceRefsJson = sourceRefsJson;
        this.status = status; this.createdAt = now; this.updatedAt = now;
    }

    public void contradict(String conflictGroup) {
        this.status = SummaryClaimStatus.CONTRADICTED;
        this.conflictGroup = conflictGroup;
        this.updatedAt = Instant.now();
    }
    public void missingSource() { this.status = SummaryClaimStatus.MISSING_SOURCE; this.updatedAt = Instant.now(); }
    public String getId() { return id; }
    public String getSummaryId() { return summaryId; }
    public String getClaimKey() { return claimKey; }
    public String getClaimType() { return claimType; }
    public String getStatement() { return statement; }
    public String getSourceRefsJson() { return sourceRefsJson; }
    public SummaryClaimStatus getStatus() { return status; }
    public String getConflictGroup() { return conflictGroup; }
}
