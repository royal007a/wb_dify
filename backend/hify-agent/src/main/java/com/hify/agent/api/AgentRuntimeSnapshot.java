package com.hify.agent.api;

import java.util.List;

public record AgentRuntimeSnapshot(
        String versionId, String agentId, int versionNo, String snapshotDigest,
        String name, String instructions, String providerId, String modelId,
        double temperature, int maxTokens, int maxTurns, int maxContextTurns,
        List<String> enabledTools, List<AgentKnowledgeBindingSnapshot> knowledgeBindings,
        AgentWorkflowBindingSnapshot workflowBinding, List<AgentMcpToolSnapshot> mcpTools, boolean enabled
) {}
