package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.ExecutionCancelledException;
import com.hify.common.ExecutionControl;
import com.hify.common.ExecutionLifecycle;
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
 private final ExecutionLifecycle lifecycle;
 public WorkflowEngine(WorkflowApplicationService application,WorkflowRunRepository runs,WorkflowNodeRunRepository nodeRuns,KnowledgeRetrievalPort knowledge,ObjectMapper json,WorkflowGraphValidator validator,java.util.concurrent.Executor ioExecutor,Duration timeout){this(application,runs,nodeRuns,knowledge,json,validator,ioExecutor,timeout,new ExecutionLifecycle());}
 @org.springframework.beans.factory.annotation.Autowired
 public WorkflowEngine(WorkflowApplicationService application,WorkflowRunRepository runs,WorkflowNodeRunRepository nodeRuns,KnowledgeRetrievalPort knowledge,ObjectMapper json,WorkflowGraphValidator validator,@Qualifier("workflowIoExecutor") java.util.concurrent.Executor ioExecutor,@Value("${hify.workflow.timeout:60s}") Duration timeout,ExecutionLifecycle lifecycle){this.application=application;this.runs=runs;this.nodeRuns=nodeRuns;this.knowledge=knowledge;this.json=json;this.validator=validator;this.ioExecutor=ioExecutor;this.timeout=timeout;this.lifecycle=lifecycle;}

 public WorkflowRunResponse execute(String versionId,String input){
  return execute(versionId,input,ExecutionControl.withTimeout(timeout,()->false));
 }

 // Each repository call commits a small state change. Do not hold a DB connection while waiting on IO.
 public WorkflowRunResponse execute(String versionId,String input,ExecutionControl control){
  com.hify.common.TextInput.requireNoNul(versionId,input);
  control=control.withShutdown(lifecycle::isStopping);
  control.throwIfSuspended();
  WorkflowVersion version=application.requireVersion(versionId); WorkflowDraftRequest draft=WorkflowPublishedGraph.read(version,json);
  validator.validate(draft);
  draft.nodes().stream().filter(WorkflowKnowledgeSnapshots::isKnowledge).forEach(WorkflowKnowledgeSnapshots::require);
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
    if (++sequence > WorkflowGraphValidator.MAX_EXECUTION_STEPS) throw new BizException(ErrorCode.CONFLICT, "Workflow 超过 "+WorkflowGraphValidator.MAX_EXECUTION_STEPS+" 步");
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
     // Freeze the observed reason before node persistence or later signals can race it.
     throw new NodeFailure(failure, stopped);
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
   String stopped = failure instanceof NodeFailure nodeFailure ? nodeFailure.stopped : stopStatus(failure, control);
   Exception observed = failure instanceof NodeFailure nodeFailure ? nodeFailure.failure : failure;
   if (stopped == null) run.fail(safe(observed), write(context.snapshot()), millis(started));
   else run.stop(stopped, safe(observed), write(context.snapshot()), millis(started));
  }
  // End/failure classification is the execution boundary. Parent delivery is separate;
  // no late cancellation/deadline/shutdown flag may rewrite this computed fact.
  // Projection/read failure must not rewrite an already committed successful execution as FAILED.
  return response(runs.save(run));
 }

 @Transactional(readOnly=true) public WorkflowRunResponse get(String id){return response(runs.findById(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Workflow Run 不存在")));}
 private NodeOutcome executeNode(WorkflowNodeSpec node,WorkflowExecutionContext ctx,ExecutionControl control){JsonNode c=node.config();String type=node.type().toUpperCase(Locale.ROOT);return switch(type){
  case "START" -> new NodeOutcome(null,null);
  case "TEMPLATE" -> {String value=ctx.resolve(required(c,"template"));String variable=text(c,"outputVariable","result");ctx.set(node.nodeKey(),variable,value);yield new NodeOutcome(null,null);}
  case "CONDITION" -> {boolean result=evaluate(required(c,"expression"),ctx);ctx.set(node.nodeKey(),text(c,"outputVariable","result"),result);yield new NodeOutcome(result,null);}
  case "KNOWLEDGE" -> {var snapshot=WorkflowKnowledgeSnapshots.require(node);String query=ctx.resolve(required(c,"query"));int topK=c.path("topK").asInt(3);var citations=WorkflowControl.call(control,ioExecutor,()->knowledge.searchSnapshot(snapshot,query,topK));if(citations.isEmpty())throw new BizException(ErrorCode.CONFLICT,"KNOWLEDGE_NO_EVIDENCE: 未检索到可用知识候选，工作流不能继续生成回答");ctx.set(node.nodeKey(),text(c,"outputVariable","citations"),citations);yield new NodeOutcome(null,null);}
  case "END" -> new NodeOutcome(null,ctx.resolve(required(c,"output")));
  default -> throw new BizException(ErrorCode.PARAM_ERROR,"不支持的节点类型: "+type);
 };}
 private String next(WorkflowNodeSpec node,List<WorkflowEdgeSpec> edges,Boolean condition){if(edges.isEmpty())throw new BizException(ErrorCode.CONFLICT,"节点没有出边: "+node.nodeKey());if(node.type().equalsIgnoreCase("CONDITION")){String expected=String.valueOf(condition);return edges.stream().filter(e->!e.defaultBranch()&&expected.equalsIgnoreCase(e.condition())).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseGet(()->edges.stream().filter(WorkflowEdgeSpec::defaultBranch).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseThrow(()->new BizException(ErrorCode.CONFLICT,"条件没有匹配或默认分支")));}return edges.stream().filter(e->e.condition()==null||e.condition().isBlank()||e.defaultBranch()).map(WorkflowEdgeSpec::targetNodeKey).findFirst().orElseThrow(()->new BizException(ErrorCode.CONFLICT,"节点没有无条件出边"));}
 private boolean evaluate(String expression,WorkflowExecutionContext context){
  return WorkflowExpression.parse(expression).evaluate(context);
 }
 private WorkflowRunResponse response(WorkflowRun r){Map<String,Object> context;try{context=json.readValue(r.getContextJson(),new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){context=Map.of();}var nodes=nodeRuns.findByWorkflowRunIdOrderBySequenceNo(r.getId()).stream().map(n->new WorkflowRunResponse.NodeRunResponse(n.getSequenceNo(),n.getNodeKey(),n.getNodeType(),n.getStatus(),n.getOutputJson(),n.getErrorMessage(),n.getElapsedMs())).toList();return new WorkflowRunResponse(r.getId(),r.getWorkflowVersionId(),r.getWorkflowDigest(),r.getStatus(),r.getInputText(),r.getOutputText(),context,r.getErrorMessage(),r.getElapsedMs(),nodes,r.getCreatedAt(),r.getFinishedAt());}
 private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private String required(JsonNode node,String field){String value=node.path(field).asText();if(value.isBlank())throw new BizException(ErrorCode.PARAM_ERROR,"节点缺少配置: "+field);return value;}private String text(JsonNode n,String f,String d){String v=n.path(f).asText();return v.isBlank()?d:v;}
 private long millis(Instant started){return Math.max(0,Duration.between(started,Instant.now()).toMillis());}private String safe(Exception e){String v=e.getMessage();if(v==null||v.isBlank())v=e.getClass().getSimpleName();return v.substring(0,Math.min(900,v.length()));}
 private record NodeOutcome(Boolean condition,String output){}
 private static final class NodeFailure extends RuntimeException {
  final Exception failure; final String stopped;
  NodeFailure(Exception failure,String stopped){super(failure);this.failure=failure;this.stopped=stopped;}
 }
 private String stopStatus(Exception failure,ExecutionControl control){if(control.isSuspended())return "INTERRUPTED";if(failure instanceof ExecutionCancelledException||control.isCancelled())return "CANCELLED";if(failure instanceof WorkflowControl.DeadlineExceeded||control.isExpired())return "TIMED_OUT";return null;}
}
