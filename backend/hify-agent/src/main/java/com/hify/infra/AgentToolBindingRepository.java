package com.hify.infra;

import com.hify.domain.AgentToolBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AgentToolBindingRepository
        extends JpaRepository<AgentToolBinding, AgentToolBinding.Key> {
    @Query("select binding from AgentToolBinding binding where binding.agentId = :agentId order by binding.toolName")
    List<AgentToolBinding> findByAgentId(@Param("agentId") String agentId);

    @Query("select binding from AgentToolBinding binding where binding.agentId in :agentIds order by binding.agentId, binding.toolName")
    List<AgentToolBinding> findByAgentIds(@Param("agentIds") Collection<String> agentIds);

    @Modifying
    @Query("delete from AgentToolBinding binding where binding.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") String agentId);
}
