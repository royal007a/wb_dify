package com.hify.infra;

import com.hify.domain.AgentDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, String> {
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, String id);
    Optional<AgentDefinition> findByIdAndArchivedAtIsNull(String id);
    Page<AgentDefinition> findAllByArchivedAtIsNull(Pageable pageable);
}
