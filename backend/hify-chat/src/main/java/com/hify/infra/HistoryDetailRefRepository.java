package com.hify.infra;

import com.hify.domain.HistoryDetailRef;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface HistoryDetailRefRepository extends JpaRepository<HistoryDetailRef, String> {
    Optional<HistoryDetailRef> findByRunIdAndSourceMessageIndexAndContentDigest(
            String runId, int sourceMessageIndex, String contentDigest);
    List<HistoryDetailRef> findByRunIdOrderBySourceMessageIndexAsc(String runId);
    List<HistoryDetailRef> findByConversationIdOrderByOccurredAtAsc(String conversationId);
}
