package com.hify.knowledge.api;

import java.util.List;

public interface KnowledgeRetrievalPort {
    List<KnowledgeCitation> search(String knowledgeBaseId, String query, int topK);
    KnowledgeChunkResponse requireCanonicalChunk(String chunkId, String expectedDigest);
}
