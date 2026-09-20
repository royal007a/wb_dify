package com.hify.knowledge.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase {
    @Id private String id;
    private String name;
    private String description;
    private int chunkSize;
    private int chunkOverlap;
    private boolean enabled;
    private Instant archivedAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long rowVersion;

    protected KnowledgeBase() {}

    public KnowledgeBase(String id, String name, String description, int chunkSize, int chunkOverlap, Instant now) {
        this.id = id; this.name = name; this.description = description; this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap; this.enabled = true; this.createdAt = now; this.updatedAt = now;
    }

    public void update(String name, String description, int chunkSize, int chunkOverlap, boolean enabled) {
        this.name = name; this.description = description; this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap; this.enabled = enabled; this.updatedAt = Instant.now();
    }
    public void archive() { this.enabled = false; this.archivedAt = Instant.now(); this.updatedAt = this.archivedAt; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getChunkSize() { return chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public boolean isEnabled() { return enabled; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
