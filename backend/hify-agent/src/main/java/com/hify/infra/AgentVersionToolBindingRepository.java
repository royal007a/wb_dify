package com.hify.infra;

import com.hify.domain.AgentVersionToolBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentVersionToolBindingRepository
        extends JpaRepository<AgentVersionToolBinding, AgentVersionToolBinding.Key> {
    @Query("select binding from AgentVersionToolBinding binding where binding.agentVersionId = :versionId order by binding.toolName")
    List<AgentVersionToolBinding> findByVersionId(@Param("versionId") String versionId);
}
