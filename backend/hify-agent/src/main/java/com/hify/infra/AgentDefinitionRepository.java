package com.hify.infra;

import com.hify.domain.AgentDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, String> {
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, String id);
}
