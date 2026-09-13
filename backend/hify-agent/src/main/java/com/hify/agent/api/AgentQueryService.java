package com.hify.agent.api;

public interface AgentQueryService {
    AgentRuntimeSnapshot requirePublished(String agentId);
    AgentRuntimeSnapshot requireVersion(String versionId);
}
