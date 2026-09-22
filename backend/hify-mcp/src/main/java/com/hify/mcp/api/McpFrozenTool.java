package com.hify.mcp.api;

import java.util.Map;

public record McpFrozenTool(String serverId, long serverRevision, String serverSchemaDigest,
                            String toolName, String runtimeToolName, String description,
                            Map<String,Object> inputSchema, String toolSchemaDigest, String risk) {}
