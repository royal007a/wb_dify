package com.hify.workflow.api;

public record WorkflowCapabilitySnapshot(String workflowId, String workflowVersionId,
                                         int versionNo, String checksum) {}
