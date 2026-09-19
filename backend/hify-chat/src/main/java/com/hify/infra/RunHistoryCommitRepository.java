package com.hify.infra;

import com.hify.domain.RunHistoryCommit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunHistoryCommitRepository extends JpaRepository<RunHistoryCommit, Long> {
    Optional<RunHistoryCommit> findByRunIdAndOperationId(String runId, String operationId);
    Optional<RunHistoryCommit> findByRunIdAndRevision(String runId, long revision);
    Optional<RunHistoryCommit> findTopByRunIdOrderByRevisionDesc(String runId);
    List<RunHistoryCommit> findByRunIdOrderByRevisionAsc(String runId);
}
