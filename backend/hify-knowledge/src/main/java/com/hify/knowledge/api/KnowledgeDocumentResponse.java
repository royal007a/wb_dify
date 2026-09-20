package com.hify.knowledge.api;

import java.time.Instant;

public record KnowledgeDocumentResponse(String id, String knowledgeBaseId, String name, String mediaType,
                                        long fileSize, String checksum, int documentVersion, String indexingState,
                                        String errorMessage, int chunkCount, Instant createdAt, Instant updatedAt) {}
