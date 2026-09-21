package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowNodeEntity; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface WorkflowNodeRepository extends JpaRepository<WorkflowNodeEntity,String>{List<WorkflowNodeEntity> findByWorkflowIdOrderByNodeKey(String id);void deleteByWorkflowId(String id);}
