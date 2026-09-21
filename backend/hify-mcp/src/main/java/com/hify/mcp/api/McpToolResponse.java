package com.hify.mcp.api;
import com.fasterxml.jackson.databind.JsonNode;
public record McpToolResponse(String name,String description,JsonNode inputSchema,String risk,String schemaDigest,long serverRevision){}
