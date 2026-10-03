package com.hify.infra;

import com.hify.domain.RunEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RunEventRepository extends JpaRepository<RunEvent, Long> {
    List<RunEvent> findByRunIdOrderByIdAsc(String runId);
    List<RunEvent> findByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long id);
    List<RunEvent> findByRunIdAndIdGreaterThanOrderByIdAsc(String runId, Long id, org.springframework.data.domain.Pageable page);
    Optional<RunEvent> findTopByRunIdOrderBySequenceNoDesc(String runId);
    Optional<RunEvent> findFirstByRunIdAndEventTypeInOrderByIdAsc(String runId, List<String> types);
}
