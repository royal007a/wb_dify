package com.hify.agent.api;

public record AgentKnowledgeBindingSnapshot(
        String knowledgeBaseId, int topK, int priority,
        String corpusVersionId, String manifestDigest
) {}
