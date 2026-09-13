package com.hify.application;

import com.hify.domain.AgentDefinition;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.intent.IntentDecision;
import com.hify.intent.LayeredIntentRouter;
import com.hify.provider.api.ProviderQueryService;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.runtime.ModelClientFactory;
import org.springframework.stereotype.Service;

@Service
public class IntentRoutingApplicationService {
    private final AgentDefinitionRepository agents;
    private final ProviderQueryService providers;
    private final ModelClientFactory modelClients;
    private final LayeredIntentRouter router;

    public IntentRoutingApplicationService(AgentDefinitionRepository agents,
                                           ProviderQueryService providers,
                                           ModelClientFactory modelClients,
                                           LayeredIntentRouter router) {
        this.agents = agents;
        this.providers = providers;
        this.modelClients = modelClients;
        this.router = router;
    }

    public IntentDecision decide(String agentId, String input) {
        AgentDefinition agent = agents.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        if (!agent.isEnabled()) throw new IllegalStateException("Agent is disabled: " + agentId);
        ProviderRuntimeConfig provider = providers.requireEnabled(agent.getProviderId());
        String model = agent.getModel() == null || agent.getModel().isBlank()
                ? provider.defaultModelId() : agent.getModel();

        return router.route(input, () -> modelClients.create(provider), model);
    }
}
