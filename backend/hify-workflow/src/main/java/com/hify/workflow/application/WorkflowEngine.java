package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.*;
import com.hify.workflow.domain.*;
import com.hify.workflow.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration; import java.time.Instant; import java.util.*;

@Service
public class WorkflowEngine {
 private final WorkflowApplicationService application; private final WorkflowRunRepository runs; private final WorkflowNodeRunRepository nodeRuns; private final KnowledgeRetrievalPort knowledge; private final ObjectMapper json;
 private final WorkflowGraphValidator validator;
 private final java.util.concurrent.Executor ioExecutor;
 private final Duration timeout;
 public WorkflowEngine(WorkflowApplicationService application,WorkflowRunRepository runs,WorkflowNodeRunRepository nodeRuns,KnowledgeRetrievalPort knowledge,ObjectMapper json,WorkflowGraphValidator validator,@Qualifier("workflowIoExecutor") java.util.concurrent.Executor ioExecutor,@Value("${hify.workflow.timeout:60s}") Duration timeout){this.application=application;this.runs=runs;this.nodeRuns=nodeRuns;this.knowledge=knowledge;this.json=json;this.validator=validator;this.ioExecutor=ioExecutor;this.timeout=timeout;}

 public WorkflowRunResponse execute(String versionId,String input){
  return execute(versionId,input,ExecutionControl.withTimeout(timeout,()->false));
 }

 // Each repository call commits a small state change. Do not hold a DB connection while waiting on IO.
 public WorkflowRunResponse execute(String versionId,String input,ExecutionControl control){
  WorkflowVersion version=application.requireVersion(versionId); WorkflowDraftRequest draft=read(version.getDslJson());
  validator.validate(draft);
  Map<String,WorkflowNodeSpec> nodeMap=new LinkedHashMap<>();draft.nodes().forEach(n->nodeMap.put(n.nodeKey(),n));Map<String,List<WorkflowEdgeSpec>> edgeMap=new HashMap<>();for(var e:draft.edges())edgeMap.computeIfAbsent(e.sourceNodeKey(),k->new ArrayList<>()).add(e);
  String current=nodeMap.values().stream().filter(n->n.type().equalsIgnoreCase("START")).findFirst().orElseThrow().nodeKey();
  WorkflowRun run = runs.save(new WorkflowRun(UUID.randomUUID().toString(), versionId, version.getChecksum(), input, Instant.now()));
  WorkflowExecutionContext context = new WorkflowExecutionContext(current, input);
  Instant started = Instant.now();
  int sequence = 0;
  boolean reachedEnd = false;
  try {
   while (current != null) {
    WorkflowControl.check(control);
    if (++sequence > 50) throw new BizException(ErrorCode.CONFLICT, "Workflow 超过 50 步");
    WorkflowNodeSpec node = nodeMap.get(current);
    if (node == null) throw new BizException(ErrorCode.CONFLICT, "目标节点不存在");
    Instant nodeStarted = Instant.now();
    WorkflowNodeRun nodeRun = new WorkflowNodeRun(UUID.randomUUID().toString(), run.getId(), sequence, node.nodeKey(), node.type(), nodeStarted);
    nodeRuns.save(nodeRun);
    NodeOutcome outcome;
    try {
     WorkflowControl.check(control);
     outcome = executeNode(node, context, control);
     WorkflowControl.check(control);
     nodeRun.succeed(write(context.snapshot()), millis(nodeStarted));
     nodeRuns.save(nodeRun);
    } catch (Exception failure) {
     String stopped = stopStatus(failure, control);
     if (stopped == null) nodeRun.fail(safe(failure), millis(nodeStarted));
     else nodeRun.stop(stopped, safe(failure), millis(nodeStarted));
     nodeRuns.save(nodeRun);
     throw failure;
    }
    if (node.type().equalsIgnoreCase("END")) {
     WorkflowControl.check(control);
     run.succeed(outcome.output(), write(context.snapshot()), millis(started));
     reachedEnd = true;
     break;
    }
    current = next(node, edgeMap.getOrDefault(node.nodeKey(), List.of()), outcome.condition());
   }
   if (!reachedEnd) throw new BizException(ErrorCode.CONFLICT, "Workflow 未到达 END");
  } catch (Exception failure) {
   String stopped = stopStatus(failure, control);
   if (stopped == null) run.fail(safe(failure), write(context.snapshot()), millis(started));
   else run.stop(stopped, safe(failure), write(context.snapshot()), millis(started));
  }
  if (control.isCancelled()) run.stop("CANCELLED", "Workflow cancelled", write(context.snapshot()), millis(started));
  else if (control.isExpired()) run.stop("TIMED_OUT", "Workflow deadline exceeded", write(context.snapshot()), millis(started));
  // Projection/read failure must not rewrite an already committed successful execution as FAILED.
  return response(runs.save(run));
 }

 @Transactional(readOnly=true) public WorkflowRunResponse get(String id){return response(runs.findById(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Workflow Run 不存在")));}
 private NodeOutcome executeNode(WorkflowNodeSpec node,WorkflowExecutionContext ctx,ExecutionControl control){JsonNode c=node.config();String type=node.type().toUpperCase(Locale.ROOT);return switch(type){
  case "START" -> new NodeOutcome(null,null);
  case "TEMPLATE" -> {String value=ctx.resolve(required(c,"template"));String variable=text(c,"outputVariable","result");ctx.set(node.nodeKey(),variable,value);yield new NodeOutcome(null,null);}
  case "CONDITION" -> {boolean result=evaluate(required(c,"expression"),ctx);ctx.set(node.nodeKey(),text(c,"outputVariable","result"),result);yield new NodeOutcome(result,null);}
  case "KNOWLEDGE" -> {String base=required(c,"knowledgeBaseId"),query=ctx.resolve(required(c,"query"));int topK=c.path("topK").asInt(3);var citations=WorkflowControl.call(control,ioExecutor,()->knowledge.search(base,query,topK));ctx.set(node.nodeKey(),text(c,"outputVariable","citations"),citations);yield new NodeOutcome(null,null);}
  case "END" -> new NodeOutcome(null,ctx.resolve(required(c,"output")));
  default -> throw new BizException(ErrorCode.PARAM_ERROR,"不支持的节点类型: "+type);
 };}
 private String next(WorkflowNodeSpec node,List<WorkflowEdgeSpec> edges,Boolean condition){if(edges.isEmpty())throw new BizException(ErrorCode.CONFLICT,"节点没有出边: "+node.nodeKey());if(node.type().equalsIgnoreCase("CONDITION")){String expected=String.valueOf(condition);return edges.stream().filter(e->!e.defaultBranch()&&expected.equalsIgnoreCase(e.condition())).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseGet(()->edges.stream().filter(WorkflowEdgeSpec::defaultBranch).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseThrow(()->new BizException(ErrorCode.CONFLICT,"条件没有匹配或默认分支")));}return edges.stream().filter(e->e.condition()==null||e.condition().isBlank()||e.defaultBranch()).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseThrow(()->new BizException(ErrorCode.CONFLICT,"节点没有无条件出边"));}
 private boolean evaluate(String expression,WorkflowExecutionContext context){
  // Locate DSL operators before substituting data, so user text cannot introduce operators.
  String value=expression.trim();
  int contains=value.indexOf(" contains ");
  if(contains>0)return context.resolve(strip(value.substring(0,contains))).contains(context.resolve(strip(value.substring(contains+10))));
  int ne=value.indexOf(" != ");
  if(ne>0)return !context.resolve(strip(value.substring(0,ne))).equals(context.resolve(strip(value.substring(ne+4))));
  int eq=value.indexOf(" == ");
  if(eq>0)return context.resolve(strip(value.substring(0,eq))).equals(context.resolve(strip(value.substring(eq+4))));
  String resolved=context.resolve(value);
  if(resolved.equalsIgnoreCase("true")||resolved.equalsIgnoreCase("false"))return Boolean.parseBoolean(resolved);
  throw new BizException(ErrorCode.PARAM_ERROR,"条件表达式只支持布尔值、contains、== 和 !=");
 }
 private String strip(String value){String out=value.trim();if((out.startsWith("'")&&out.endsWith("'"))||(out.startsWith("\"")&&out.endsWith("\"")))return out.substring(1,out.length()-1);return out;}
 private WorkflowRunResponse response(WorkflowRun r){Map<String,Object> context;try{context=json.readValue(r.getContextJson(),new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){context=Map.of();}var nodes=nodeRuns.findByWorkflowRunIdOrderBySequenceNo(r.getId()).stream().map(n->new WorkflowRunResponse.NodeRunResponse(n.getSequenceNo(),n.getNodeKey(),n.getNodeType(),n.getStatus(),n.getOutputJson(),n.getErrorMessage(),n.getElapsedMs())).toList();return new WorkflowRunResponse(r.getId(),r.getWorkflowVersionId(),r.getWorkflowDigest(),r.getStatus(),r.getInputText(),r.getOutputText(),context,r.getErrorMessage(),r.getElapsedMs(),nodes,r.getCreatedAt(),r.getFinishedAt());}
 private WorkflowDraftRequest read(String value){try{return json.readValue(value,WorkflowDraftRequest.class);}catch(Exception e){throw new BizException(ErrorCode.CONFLICT,"发布版本 DSL 无法解析");}}
 private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private String required(JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"节点缺少配置: "+field);return value;}private String text(JsonNode n,String f,String d){String v=n.path(f).asText();return v.isBlank()?d:v;}
 private long millis(Instant started){return Math.max(0,Duration.between(started,Instant.now()).toMillis());}private String safe(Exception e){String v=e.getMessage();if(v==null||v.isBlank())v=e.getClass().getSimpleName();return v.substring(0,Math.min(900,v.length()));}
 private record NodeOutcome(Boolean condition,String output){}
 private String stopStatus(Exception failure,ExecutionControl control){if(failure instanceof ExecutionCancelledException||control.isCancelled())return "CANCELLED";if(failure instanceof WorkflowControl.DeadlineExceeded||control.isExpired())return "TIMED_OUT";return null;}
}
