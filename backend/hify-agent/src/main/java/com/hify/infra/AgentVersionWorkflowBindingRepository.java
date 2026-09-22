package com.hify.infra;

import com.hify.domain.AgentVersionWorkflowBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface AgentVersionWorkflowBindingRepository extends JpaRepository<AgentVersionWorkflowBinding,String> {
    List<AgentVersionWorkflowBinding> findByAgentVersionIdIn(Collection<String> versionIds);
}
