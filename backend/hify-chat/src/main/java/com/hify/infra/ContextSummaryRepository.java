package com.hify.infra;

import com.hify.domain.ContextSummary;
import com.hify.domain.ContextSummaryKind;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ContextSummaryRepository extends JpaRepository<ContextSummary, String> {
    Optional<ContextSummary> findTopByRunIdAndKindOrderBySummaryVersionDesc(String runId, ContextSummaryKind kind);
    List<ContextSummary> findByRunIdOrderBySummaryVersionAsc(String runId);
}
