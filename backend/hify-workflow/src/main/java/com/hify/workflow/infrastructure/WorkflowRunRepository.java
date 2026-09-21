package com.hify.workflow.infrastructure;
import com.hify.workflow.domain.WorkflowRun; import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun,String>{}
