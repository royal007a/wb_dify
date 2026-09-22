package com.hify.workflow.api;
import java.time.Instant; import java.util.List;
public record WorkflowVersionDetail(String id,String workflowId,int versionNo,int schemaVersion,String checksum,List<WorkflowNodeSpec> nodes,List<WorkflowEdgeSpec> edges,Instant createdAt) {}
