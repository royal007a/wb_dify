package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowNodeRun; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRun,String>{List<WorkflowNodeRun> findByWorkflowRunIdOrderBySequenceNo(String id);}
