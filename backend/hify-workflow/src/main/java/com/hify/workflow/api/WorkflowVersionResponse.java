package com.hify.workflow.api;

import java.time.Instant;
public record WorkflowVersionResponse(String id,String workflowId,int versionNo,int schemaVersion,String checksum,Instant createdAt) {}
