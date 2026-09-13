package com.hify.application;

import com.hify.domain.AgentDefinition;
import com.hify.domain.ModelProvider;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.infra.ModelProviderRepository;
import com.hify.intent.IntentDecision;
import com.hify.intent.LayeredIntentRouter;
import com.hify.runtime.ModelClientFactory;
import org.springframework.stereotype.Service;

@Service
public class IntentRoutingApplicationService {
    private final AgentDefinitionRepository agents;
    private final ModelProviderRepository providers;
    private final ModelClientFactory modelClients;
    private final LayeredIntentRouter router;

    public IntentRoutingApplicationService(AgentDefinitionRepository agents,
                                           ModelProviderRepository providers,
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
        ModelProvider provider = providers.findById(agent.getProviderId())
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + agent.getProviderId()));
        String model = agent.getModel() == null || agent.getModel().isBlank()
                ? provider.getDefaultModel() : agent.getModel();

        return router.route(input, () -> modelClients.create(provider), model);
    }
}
