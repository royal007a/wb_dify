package com.hify.agent.api;

import java.time.Instant;

public record AgentVersionResponse(String id, String agentId, int versionNo,
                                   String snapshotDigest, Instant createdAt) {}
