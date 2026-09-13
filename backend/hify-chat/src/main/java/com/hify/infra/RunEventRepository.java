package com.hify.infra;

import com.hify.domain.RunEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunEventRepository extends JpaRepository<RunEvent, Long> {
    List<RunEvent> findByRunIdOrderByIdAsc(String runId);
    List<RunEvent> findByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long id);
    Optional<RunEvent> findTopByRunIdOrderBySequenceNoDesc(String runId);
}
