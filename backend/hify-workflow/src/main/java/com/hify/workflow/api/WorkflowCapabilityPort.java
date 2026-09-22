package com.hify.workflow.api;

public interface WorkflowCapabilityPort {
    WorkflowCapabilitySnapshot freeze(String workflowId);
    java.util.Map<String,WorkflowCapabilitySnapshot> freezeAll(java.util.Collection<String> workflowIds);
    WorkflowRunResponse execute(String workflowVersionId, String input);
}
