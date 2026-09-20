package com.hify.infra;

import com.hify.domain.ContextSummaryClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContextSummaryClaimRepository extends JpaRepository<ContextSummaryClaim, String> {
    List<ContextSummaryClaim> findBySummaryId(String summaryId);
    List<ContextSummaryClaim> findByClaimKey(String claimKey);
}
