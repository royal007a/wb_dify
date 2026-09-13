package com.hify.infra;

import com.hify.domain.AgentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, String> {
    long countByAgentId(String agentId);
    List<AgentVersion> findByAgentIdOrderByVersionNoDesc(String agentId);
}
