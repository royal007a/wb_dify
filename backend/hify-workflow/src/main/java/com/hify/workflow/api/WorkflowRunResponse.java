package com.hify.workflow.api;
import java.time.Instant;
import java.util.List;
import java.util.Map;
public record WorkflowRunResponse(String id,String workflowVersionId,String workflowDigest,String status,String input,
                                  String output,Map<String,Object> context,String errorMessage,Long elapsedMs,
                                  List<NodeRunResponse> nodes,Instant createdAt,Instant finishedAt) {
    public record NodeRunResponse(int sequence,String nodeKey,String nodeType,String status,String outputJson,String errorMessage,Long elapsedMs){}
}
