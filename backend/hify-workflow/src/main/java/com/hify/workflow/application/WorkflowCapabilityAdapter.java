package com.hify.workflow.application;

import com.hify.workflow.api.WorkflowCapabilityPort;
import com.hify.workflow.api.WorkflowCapabilitySnapshot;
import com.hify.workflow.api.WorkflowRunResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowCapabilityAdapter implements WorkflowCapabilityPort {
    private final WorkflowApplicationService workflows;
    private final WorkflowEngine engine;

    public WorkflowCapabilityAdapter(WorkflowApplicationService workflows, WorkflowEngine engine) {
        this.workflows = workflows;
        this.engine = engine;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowCapabilitySnapshot freeze(String workflowId) {
        return workflows.publishedSnapshot(workflowId);
    }
    @Override @Transactional(readOnly = true)
    public java.util.Map<String,WorkflowCapabilitySnapshot> freezeAll(java.util.Collection<String> workflowIds) {
        return workflows.publishedSnapshots(workflowIds);
    }

    @Override
    public WorkflowRunResponse execute(String workflowVersionId, String input) {
        return engine.execute(workflowVersionId, input);
    }
}
