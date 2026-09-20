package com.hify.knowledge.infrastructure;

import com.hify.knowledge.domain.DocumentIndexTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DocumentIndexTaskRepository extends JpaRepository<DocumentIndexTask, String> {
    Optional<DocumentIndexTask> findByDocumentIdAndDocumentVersion(String documentId, int version);
}
