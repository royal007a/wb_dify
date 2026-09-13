package com.hify.agent.api;

import java.util.List;

public record AgentRuntimeSnapshot(
        String versionId, String agentId, int versionNo, String snapshotDigest,
        String name, String instructions, String providerId, String modelId,
        double temperature, int maxTokens, int maxTurns, int maxContextTurns,
        List<String> enabledTools, boolean enabled
) {}
