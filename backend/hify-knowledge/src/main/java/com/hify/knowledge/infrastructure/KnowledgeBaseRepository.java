package com.hify.knowledge.infrastructure;

import com.hify.knowledge.domain.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, String> {
    Page<KnowledgeBase> findByArchivedAtIsNullAndNameContainingIgnoreCase(String keyword, Pageable pageable);
    Optional<KnowledgeBase> findByIdAndArchivedAtIsNull(String id);
    boolean existsByNameAndArchivedAtIsNull(String name);
    boolean existsByNameAndIdNotAndArchivedAtIsNull(String name, String id);
}
