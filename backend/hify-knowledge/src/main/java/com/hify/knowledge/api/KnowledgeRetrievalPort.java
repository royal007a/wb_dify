package com.hify.knowledge.api;

import java.util.List;

public interface KnowledgeRetrievalPort {
    List<KnowledgeCitation> search(String knowledgeBaseId, String query, int topK);
    KnowledgeCorpusSnapshot freeze(String knowledgeBaseId);
    KnowledgeCorpusSnapshot currentSnapshot(String knowledgeBaseId);
    List<KnowledgeCitation> searchRevision(String corpusVersionId, String query, int topK);
    /** Search only this exact published corpus; mismatched or incomplete evidence fails closed. */
    List<KnowledgeCitation> searchSnapshot(KnowledgeCorpusSnapshot snapshot, String query, int topK);
    KnowledgeChunkResponse requireCanonicalChunk(String chunkId, String expectedDigest);
}
