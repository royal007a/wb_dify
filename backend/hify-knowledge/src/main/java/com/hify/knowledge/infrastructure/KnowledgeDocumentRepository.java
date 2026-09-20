package com.hify.knowledge.infrastructure;

import com.hify.knowledge.domain.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {
    Page<KnowledgeDocument> findByKnowledgeBaseIdAndArchivedAtIsNull(String knowledgeBaseId, Pageable pageable);
    Optional<KnowledgeDocument> findByIdAndArchivedAtIsNull(String id);
    List<KnowledgeDocument> findByKnowledgeBaseIdAndArchivedAtIsNull(String knowledgeBaseId);
}
