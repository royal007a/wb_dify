package com.hify.knowledge.api;

public record KnowledgeChunkResponse(String chunkId, String documentId, int documentVersion, int ordinal,
                                     String content, String digest, int tokenCount) {}
