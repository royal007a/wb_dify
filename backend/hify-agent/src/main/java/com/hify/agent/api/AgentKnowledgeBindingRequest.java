package com.hify.agent.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AgentKnowledgeBindingRequest(@NotNull List<@Valid AgentKnowledgeBindingInput> bindings) {}
