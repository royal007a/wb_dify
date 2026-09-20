package com.hify.infra;

import com.hify.domain.HistoryDetailRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface HistoryDetailRefRepository extends JpaRepository<HistoryDetailRef, String> {
    Optional<HistoryDetailRef> findByRunIdAndSourceMessageIndexAndContentDigest(
            String runId, int sourceMessageIndex, String contentDigest);
    List<HistoryDetailRef> findByRunIdOrderBySourceMessageIndexAsc(String runId);
    List<HistoryDetailRef> findByConversationIdOrderByOccurredAtAsc(String conversationId);

    @Query(value = """
            SELECT * FROM history_detail_refs
             WHERE conversation_id = :conversationId
               AND (
                    LOWER(search_text) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(keywords_text) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR LOWER(entities_text) LIKE LOWER(CONCAT('%', :query, '%'))
                 OR to_tsvector('simple', COALESCE(search_text, '') || ' ' || COALESCE(keywords_text, ''))
                    @@ plainto_tsquery('simple', :query)
               )
             ORDER BY ts_rank_cd(
                        to_tsvector('simple', COALESCE(search_text, '') || ' ' || COALESCE(keywords_text, '')),
                        plainto_tsquery('simple', :query)) DESC,
                      occurred_at DESC
             LIMIT :candidateLimit
            """, nativeQuery = true)
    List<HistoryDetailRef> searchPostgres(@Param("conversationId") String conversationId,
                                          @Param("query") String query,
                                          @Param("candidateLimit") int candidateLimit);
}
