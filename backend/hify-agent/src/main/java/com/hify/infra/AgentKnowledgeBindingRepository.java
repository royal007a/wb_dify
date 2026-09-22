package com.hify.infra;

import com.hify.domain.AgentKnowledgeBinding;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface AgentKnowledgeBindingRepository extends JpaRepository<AgentKnowledgeBinding,AgentKnowledgeBinding.Key> {
    @Query("select b from AgentKnowledgeBinding b where b.agentId=:agentId order by b.priority,b.knowledgeBaseId") List<AgentKnowledgeBinding> findByAgentId(@Param("agentId") String agentId);
    @Query("select b from AgentKnowledgeBinding b where b.agentId in :agentIds order by b.agentId,b.priority,b.knowledgeBaseId") List<AgentKnowledgeBinding> findByAgentIds(@Param("agentIds") Collection<String> agentIds);
    @Modifying @Query("delete from AgentKnowledgeBinding b where b.agentId=:agentId") void deleteByAgentId(@Param("agentId") String agentId);
}
