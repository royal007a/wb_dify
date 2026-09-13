package com.hify.infra;

import com.hify.domain.AgentRun;
import com.hify.domain.RunState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    Optional<AgentRun> findByConversationIdAndIdempotencyKey(String conversationId, String idempotencyKey);
    List<AgentRun> findByStateIn(Collection<RunState> states);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentRun r
               set r.cancelRequestedAt = :requestedAt,
                   r.updatedAt = :requestedAt,
                   r.version = r.version + 1
             where r.id = :id
               and r.state = :expectedState
               and r.cancelRequestedAt is null
            """)
    int requestCancel(@Param("id") String id,
                      @Param("expectedState") RunState expectedState,
                      @Param("requestedAt") Instant requestedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update AgentRun r
               set r.state = :state,
                   r.terminalReason = :terminalReason,
                   r.outputMessage = :outputMessage,
                   r.turns = :turns,
                   r.toolCalls = :toolCalls,
                   r.updatedAt = :updatedAt,
                   r.version = r.version + 1
             where r.id = :id
               and r.state = :expectedState
               and r.version = :expectedVersion
            """)
    int finishTerminal(@Param("id") String id,
                       @Param("expectedState") RunState expectedState,
                       @Param("expectedVersion") long expectedVersion,
                       @Param("state") RunState state,
                       @Param("terminalReason") String terminalReason,
                       @Param("outputMessage") String outputMessage,
                       @Param("turns") int turns,
                       @Param("toolCalls") int toolCalls,
                       @Param("updatedAt") Instant updatedAt);
}
