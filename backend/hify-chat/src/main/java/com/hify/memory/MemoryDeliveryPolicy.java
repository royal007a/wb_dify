package com.hify.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.AgentQueryService;
import com.hify.application.KnowledgeCompletionVerifier;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.RunCheckpointRepository;
import org.springframework.stereotype.Service;

/** Canonical recovery history is not a delivery channel for knowledge-gated model output. */
@Service
public class MemoryDeliveryPolicy {
    private final AgentRunRepository runs;
    private final RunCheckpointRepository checkpoints;
    private final org.springframework.beans.factory.ObjectProvider<AgentQueryService> agents;
    private final ObjectMapper json;

    public MemoryDeliveryPolicy(AgentRunRepository runs, RunCheckpointRepository checkpoints,
                                org.springframework.beans.factory.ObjectProvider<AgentQueryService> agents, ObjectMapper json) {
        this.runs=runs;this.checkpoints=checkpoints;this.agents=agents;this.json=json;
    }

    public boolean rawMemoryVisible(String sourceRunId) {
        var run=runs.findById(sourceRunId).orElse(null);
        if(run==null)return false;
        try {
            if(run.getAgentVersionId()!=null){
                // Tool catalog construction also installs history tools; resolve the read-only
                // Agent port on use, after bean construction, not while building that catalog.
                var bindings=agents.getObject().requireVersion(run.getAgentVersionId()).knowledgeBindings();
                if(bindings!=null&&!bindings.isEmpty())return false;
            }
            // Legacy/unpinned and resumed runs retain the gate in their durable checkpoint.
            var checkpoint=checkpoints.findTopByRunIdOrderBySequenceNoDesc(sourceRunId);
            if(checkpoint.isPresent()){
                var context=json.readTree(checkpoint.get().getContextJson());
                if(context==null||!context.isObject())return false;
                for(var claim:context.path("claims"))
                    if(KnowledgeCompletionVerifier.CLAIM.equals(claim.path("id").asText()))return false;
            }
            return true;
        }catch(Exception unavailable){
            // Missing/corrupt version or context cannot authorize release of an old projection.
            return false;
        }
    }
}
