package com.hify.infra;

import com.hify.domain.RunCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RunCheckpointRepository extends JpaRepository<RunCheckpoint, Long> {
    Optional<RunCheckpoint> findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(String runId);
    Optional<RunCheckpoint> findTopByRunIdOrderBySequenceNoDesc(String runId);
}
