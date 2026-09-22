package com.hify.knowledge.api;

public record KnowledgeCorpusSnapshot(
        String id, String knowledgeBaseId, int revisionNo,
        String manifestDigest, int chunkCount
) {}
