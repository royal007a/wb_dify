package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "history_detail_refs", uniqueConstraints = @UniqueConstraint(
        name = "uq_detail_ref_source", columnNames = {"run_id", "source_message_index", "content_digest"}))
public class HistoryDetailRef {
    @Id private String id;
    private String runId;
    private String conversationId;
    private long sourceRevision;
    private int sourceMessageIndex;
    @Enumerated(EnumType.STRING) private DetailRefKind kind;
    private String role;
    private String contentDigest;
    @Column(length = 2000) private String contentPreview;
    @Column(columnDefinition = "TEXT") private String searchText;
    @Column(length = 4000) private String keywordsText;
    @Column(length = 4000) private String entitiesText;
    private int tokenCount;
    private Instant occurredAt;
    private Instant createdAt;
    @Version private long version;

    protected HistoryDetailRef() {}

    public HistoryDetailRef(String id, String runId, String conversationId, long sourceRevision,
                            int sourceMessageIndex, DetailRefKind kind, String role,
                            String contentDigest, String contentPreview, String searchText,
                            String keywordsText, String entitiesText, int tokenCount,
                            Instant occurredAt, Instant createdAt) {
        this.id = id; this.runId = runId; this.conversationId = conversationId;
        this.sourceRevision = sourceRevision; this.sourceMessageIndex = sourceMessageIndex;
        this.kind = kind; this.role = role; this.contentDigest = contentDigest;
        this.contentPreview = contentPreview; this.searchText = searchText;
        this.keywordsText = keywordsText; this.entitiesText = entitiesText;
        this.tokenCount = tokenCount; this.occurredAt = occurredAt; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getConversationId() { return conversationId; }
    public long getSourceRevision() { return sourceRevision; }
    public int getSourceMessageIndex() { return sourceMessageIndex; }
    public DetailRefKind getKind() { return kind; }
    public String getRole() { return role; }
    public String getContentDigest() { return contentDigest; }
    public String getContentPreview() { return contentPreview; }
    public String getSearchText() { return searchText; }
    public String getKeywordsText() { return keywordsText; }
    public String getEntitiesText() { return entitiesText; }
    public int getTokenCount() { return tokenCount; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
