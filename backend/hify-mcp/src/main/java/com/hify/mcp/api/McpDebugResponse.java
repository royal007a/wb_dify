package com.hify.mcp.api;
import com.fasterxml.jackson.databind.JsonNode;
public record McpDebugResponse(String callId,String toolName,long serverRevision,String schemaDigest,JsonNode result,boolean error,long elapsedMs){}
