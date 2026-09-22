package com.hify.agent.api;
import java.util.Map;
public record AgentMcpToolSnapshot(String serverId,Long serverRevision,String serverSchemaDigest,String toolName,String runtimeToolName,String description,Map<String,Object> inputSchema,String toolSchemaDigest,String risk) {}
