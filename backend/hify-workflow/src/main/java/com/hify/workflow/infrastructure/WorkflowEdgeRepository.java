package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowEdgeEntity; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdgeEntity,String>{List<WorkflowEdgeEntity> findByWorkflowIdOrderByEdgeKey(String id);void deleteByWorkflowId(String id);}
