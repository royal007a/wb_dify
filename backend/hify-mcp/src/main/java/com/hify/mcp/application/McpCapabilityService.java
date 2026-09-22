package com.hify.mcp.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.mcp.api.McpCapabilityPort;
import com.hify.mcp.api.McpFrozenTool;
import com.hify.mcp.domain.McpServer;
import com.hify.mcp.domain.McpToolSnapshot;
import com.hify.mcp.infrastructure.McpServerRepository;
import com.hify.mcp.infrastructure.McpServerRevisionRepository;
import com.hify.mcp.infrastructure.McpToolSnapshotRepository;
import com.hify.runtime.DynamicRuntimeToolExecutor;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolDefinition;
import com.hify.runtime.ToolExecutionLease;
import com.hify.runtime.ToolRuntime;
import com.hify.common.ExecutionControl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class McpCapabilityService implements McpCapabilityPort, DynamicRuntimeToolExecutor {
 private static final String PREFIX="mcp__";
 private final McpServerRepository servers; private final McpServerRevisionRepository revisions; private final McpToolSnapshotRepository tools; private final McpProtocolClient client; private final ObjectMapper json;
 public McpCapabilityService(McpServerRepository servers,McpServerRevisionRepository revisions,McpToolSnapshotRepository tools,McpProtocolClient client,ObjectMapper json){this.servers=servers;this.revisions=revisions;this.tools=tools;this.client=client;this.json=json;}
 @Override @Transactional(readOnly=true) public List<McpFrozenTool> freeze(String serverId,List<String> toolNames){return freezeAll(Map.of(serverId,toolNames));}
 @Override @Transactional(readOnly=true) public List<McpFrozenTool> freezeAll(Map<String,List<String>> requestedByServer){if(requestedByServer.isEmpty())return List.of();Map<String,McpServer> byServer=servers.findAllById(requestedByServer.keySet()).stream().collect(java.util.stream.Collectors.toMap(McpServer::getId,s->s));List<com.hify.mcp.domain.McpServerRevision.Key> revisionKeys=new ArrayList<>();for(String id:requestedByServer.keySet()){McpServer s=byServer.get(id);if(s==null||s.getArchivedAt()!=null||!s.isEnabled()||!"READY".equals(s.getStatus()))throw new BizException(ErrorCode.CONFLICT,"MCP server is not ready: "+id);revisionKeys.add(new com.hify.mcp.domain.McpServerRevision.Key(id,s.getServerRevision()));}Map<String,com.hify.mcp.domain.McpServerRevision> revisionMap=revisions.findAllById(revisionKeys).stream().collect(java.util.stream.Collectors.toMap(r->r.getServerId()+"\u001f"+r.getServerRevision(),r->r));Map<String,McpToolSnapshot> toolMap=tools.findAllByServerIdIn(requestedByServer.keySet()).stream().collect(java.util.stream.Collectors.toMap(t->t.getServerId()+"\u001f"+t.getServerRevision()+"\u001f"+t.getToolName(),t->t,(a,b)->a));List<McpFrozenTool> result=new ArrayList<>();requestedByServer.forEach((serverId,names)->{McpServer server=byServer.get(serverId);var revision=revisionMap.get(serverId+"\u001f"+server.getServerRevision());if(revision==null)throw new BizException(ErrorCode.CONFLICT,"MCP server revision snapshot is missing; discover tools again");for(String name:new TreeSet<>(names)){McpToolSnapshot tool=toolMap.get(serverId+"\u001f"+server.getServerRevision()+"\u001f"+name);if(tool==null)throw new BizException(ErrorCode.NOT_FOUND,"MCP tool not found: "+name);if(!"READ".equals(tool.getRisk()))throw new BizException(ErrorCode.FORBIDDEN,"Only READ MCP tools may be published");result.add(new McpFrozenTool(serverId,revision.getServerRevision(),revision.getSchemaDigest(),name,runtimeName(serverId,revision.getServerRevision(),name),tool.getDescription(),schema(tool),tool.getSchemaDigest(),tool.getRisk()));}});return List.copyOf(result);}
 @Override public boolean supports(ToolDefinition definition){return definition.name().startsWith(PREFIX);}
 @Override @Transactional(readOnly=true) public ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call,ToolDefinition definition,ToolExecutionLease lease,ExecutionControl control){try{lease.assertUsable(control);Parts p=parse(call.name());var revision=revisions.findById(new com.hify.mcp.domain.McpServerRevision.Key(p.serverId,p.revision)).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Frozen MCP revision not found"));McpToolSnapshot tool=tools.findByServerIdAndServerRevisionAndToolName(p.serverId,p.revision,p.toolName).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Frozen MCP tool not found"));if(!"READ".equals(tool.getRisk()))return ToolRuntime.ExecutionResult.permissionDenied("Only READ MCP tools are allowed");lease.assertUsable(control);McpServer runtime=new McpServer(p.serverId,"runtime",revision.getEndpointUrl(),revision.getCredentialRef(),true,Instant.now());var result=client.call(runtime,p.toolName,json.valueToTree(call.arguments()));control.throwIfCancelled();return result.error()?ToolRuntime.ExecutionResult.executionFailed(result.result().toString(),false):ToolRuntime.ExecutionResult.success(result.result());}catch(BizException e){return ToolRuntime.ExecutionResult.executionFailed(e.getMessage(),true);} }
 private Map<String,Object> schema(McpToolSnapshot tool){try{return json.readValue(tool.getInputSchemaJson(),new TypeReference<>(){});}catch(Exception e){throw new BizException(ErrorCode.INTERNAL_ERROR,"Stored MCP schema is invalid");}}
 public static String runtimeName(String serverId,long revision,String toolName){Base64.Encoder e=Base64.getUrlEncoder().withoutPadding();return PREFIX+e.encodeToString(serverId.getBytes(StandardCharsets.UTF_8))+"__"+revision+"__"+e.encodeToString(toolName.getBytes(StandardCharsets.UTF_8));}
 private Parts parse(String name){try{String[] p=name.split("__",4);Base64.Decoder d=Base64.getUrlDecoder();return new Parts(new String(d.decode(p[1]),StandardCharsets.UTF_8),Long.parseLong(p[2]),new String(d.decode(p[3]),StandardCharsets.UTF_8));}catch(Exception e){throw new BizException(ErrorCode.PARAM_ERROR,"Invalid MCP runtime tool name");}}
 private record Parts(String serverId,long revision,String toolName){}
}
