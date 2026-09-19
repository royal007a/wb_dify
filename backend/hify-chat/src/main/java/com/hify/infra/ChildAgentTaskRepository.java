package com.hify.infra;

import com.hify.domain.ChildAgentTask;
import com.hify.domain.ChildTaskState;
import com.hify.domain.OutputDeliveryState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChildAgentTaskRepository extends JpaRepository<ChildAgentTask, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChildAgentTask t where t.id = :id")
    Optional<ChildAgentTask> findLockedById(@Param("id") String id);

    List<ChildAgentTask> findByStateIn(Collection<ChildTaskState> states);
    List<ChildAgentTask> findByParentRunIdAndOutputState(String parentRunId, OutputDeliveryState state);
}
