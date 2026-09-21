package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowVersion; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion,String>{long countByWorkflowId(String id);List<WorkflowVersion> findByWorkflowIdOrderByVersionNoDesc(String id);}
