package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowDefinition; import org.springframework.data.domain.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.Optional;
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition,String>{Page<WorkflowDefinition> findByArchivedAtIsNull(Pageable p);Optional<WorkflowDefinition> findByIdAndArchivedAtIsNull(String id);boolean existsByNameAndArchivedAtIsNull(String name);boolean existsByNameAndIdNotAndArchivedAtIsNull(String name,String id);}
