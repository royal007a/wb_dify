package com.hify.infra;

import com.hify.domain.AgentWorkflowBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface AgentWorkflowBindingRepository extends JpaRepository<AgentWorkflowBinding,String> {
    List<AgentWorkflowBinding> findByAgentIdIn(Collection<String> agentIds);
    void deleteByAgentId(String agentId);
}
