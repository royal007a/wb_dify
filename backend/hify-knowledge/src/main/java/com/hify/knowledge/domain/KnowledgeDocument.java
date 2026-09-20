package com.hify.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {
    @Id private String id;
    private String knowledgeBaseId;
    private String name;
    private String mediaType;
    private long fileSize;
    private String checksum;
    private int documentVersion;
    @Column(columnDefinition = "TEXT") private String canonicalContent;
    @Enumerated(EnumType.STRING) private DocumentIndexingState indexingState;
    private String errorMessage;
    private int chunkCount;
    private Instant archivedAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long rowVersion;

    protected KnowledgeDocument() {}

    public KnowledgeDocument(String id, String knowledgeBaseId, String name, String mediaType, long fileSize,
                             String checksum, String canonicalContent, Instant now) {
        this.id = id; this.knowledgeBaseId = knowledgeBaseId; this.name = name; this.mediaType = mediaType;
        this.fileSize = fileSize; this.checksum = checksum; this.documentVersion = 1;
        this.canonicalContent = canonicalContent; this.indexingState = DocumentIndexingState.PENDING;
        this.chunkCount = 0; this.createdAt = now; this.updatedAt = now;
    }

    public void processing() { indexingState = DocumentIndexingState.PROCESSING; errorMessage = null; updatedAt = Instant.now(); }
    public void indexed(int count) { indexingState = DocumentIndexingState.DONE; chunkCount = count; errorMessage = null; updatedAt = Instant.now(); }
    public void failed(String message) { indexingState = DocumentIndexingState.FAILED; errorMessage = message; updatedAt = Instant.now(); }
    public void archive() { indexingState = DocumentIndexingState.ARCHIVED; archivedAt = Instant.now(); updatedAt = archivedAt; }
    public String getId() { return id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public String getName() { return name; }
    public String getMediaType() { return mediaType; }
    public long getFileSize() { return fileSize; }
    public String getChecksum() { return checksum; }
    public int getDocumentVersion() { return documentVersion; }
    public String getCanonicalContent() { return canonicalContent; }
    public DocumentIndexingState getIndexingState() { return indexingState; }
    public String getErrorMessage() { return errorMessage; }
    public int getChunkCount() { return chunkCount; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
