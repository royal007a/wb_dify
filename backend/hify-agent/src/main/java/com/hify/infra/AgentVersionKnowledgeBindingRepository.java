package com.hify.infra;

import com.hify.domain.AgentVersionKnowledgeBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface AgentVersionKnowledgeBindingRepository extends JpaRepository<AgentVersionKnowledgeBinding,AgentVersionKnowledgeBinding.Key> {
    @Query("select b from AgentVersionKnowledgeBinding b where b.agentVersionId=:versionId order by b.priority,b.knowledgeBaseId") List<AgentVersionKnowledgeBinding> findByVersionId(@Param("versionId") String versionId);
    @Query("select b from AgentVersionKnowledgeBinding b where b.agentVersionId in :versionIds order by b.agentVersionId,b.priority,b.knowledgeBaseId") List<AgentVersionKnowledgeBinding> findByVersionIds(@Param("versionIds") Collection<String> versionIds);
}
