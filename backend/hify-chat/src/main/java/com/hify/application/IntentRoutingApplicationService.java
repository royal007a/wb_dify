package com.hify.application;

import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentRuntimeSnapshot;
import com.hify.intent.IntentDecision;
import com.hify.intent.LayeredIntentRouter;
import com.hify.provider.api.ProviderQueryService;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.runtime.ModelClientFactory;
import org.springframework.stereotype.Service;

@Service
public class IntentRoutingApplicationService {
    private final AgentQueryService agents;
    private final ProviderQueryService providers;
    private final ModelClientFactory modelClients;
    private final LayeredIntentRouter router;

    public IntentRoutingApplicationService(AgentQueryService agents,
                                           ProviderQueryService providers,
                                           ModelClientFactory modelClients,
                                           LayeredIntentRouter router) {
        this.agents = agents;
        this.providers = providers;
        this.modelClients = modelClients;
        this.router = router;
    }

    public IntentDecision decide(String agentId, String input) {
        AgentRuntimeSnapshot agent = agents.requirePublished(agentId);
        ProviderRuntimeConfig provider = providers.requireEnabled(agent.providerId());
        String model = agent.modelId() == null || agent.modelId().isBlank()
                ? provider.defaultModelId() : agent.modelId();

        return router.route(input, () -> modelClients.create(provider), model);
    }
}
