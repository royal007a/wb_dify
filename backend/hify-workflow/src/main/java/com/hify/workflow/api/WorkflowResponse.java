package com.hify.workflow.api;

import java.time.Instant;
import java.util.List;

public record WorkflowResponse(String id,String name,String description,int schemaVersion,long draftRevision,
                               String publishedVersionId,List<WorkflowNodeSpec> nodes,List<WorkflowEdgeSpec> edges,
                               Instant createdAt,Instant updatedAt) {}
