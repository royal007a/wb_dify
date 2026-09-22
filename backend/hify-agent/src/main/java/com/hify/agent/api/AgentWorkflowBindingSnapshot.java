package com.hify.agent.api;

public record AgentWorkflowBindingSnapshot(String workflowId, String workflowVersionId,
                                           Integer versionNo, String checksum) {}
