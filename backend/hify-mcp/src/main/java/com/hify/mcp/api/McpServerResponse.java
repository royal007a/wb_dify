package com.hify.mcp.api;
import java.time.Instant;
public record McpServerResponse(String id,String name,String transport,String endpointUrl,String credentialRef,boolean enabled,long serverRevision,String schemaDigest,String status,String lastError,Instant createdAt,Instant updatedAt){}
