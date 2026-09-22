package com.hify.mcp.api;

import java.util.List;

public interface McpCapabilityPort {
    List<McpFrozenTool> freeze(String serverId, List<String> toolNames);
    List<McpFrozenTool> freezeAll(java.util.Map<String,List<String>> toolNamesByServer);
}
