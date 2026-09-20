package com.hify.knowledge.api;

public record KnowledgeCitation(String chunkId, String documentId, int documentVersion, int ordinal,
                                String content, String digest, double score, int rank) {}
