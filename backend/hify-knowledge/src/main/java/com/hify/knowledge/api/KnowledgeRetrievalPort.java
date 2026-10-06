package com.hify.knowledge.api;

import java.util.List;
import com.hify.common.ExecutionControl;

public interface KnowledgeRetrievalPort {
    List<KnowledgeCitation> search(String knowledgeBaseId, String query, int topK);
    KnowledgeCorpusSnapshot freeze(String knowledgeBaseId);
    KnowledgeCorpusSnapshot currentSnapshot(String knowledgeBaseId);
    List<KnowledgeCitation> searchRevision(String corpusVersionId, String query, int topK);
    /** Compatibility boundary; production implementation also propagates into embedding. */
    default List<KnowledgeCitation> searchRevision(String corpusVersionId, String query, int topK,
                                                  ExecutionControl control) {
        control.checkActive();
        try {
            var result = searchRevision(corpusVersionId, query, topK);
            control.checkActive();
            return result;
        } catch (RuntimeException failure) {
            control.checkActive();
            throw failure;
        }
    }
    /** Search only this exact published corpus; mismatched or incomplete evidence fails closed. */
    List<KnowledgeCitation> searchSnapshot(KnowledgeCorpusSnapshot snapshot, String query, int topK);
    KnowledgeChunkResponse requireCanonicalChunk(String chunkId, String expectedDigest);
}
