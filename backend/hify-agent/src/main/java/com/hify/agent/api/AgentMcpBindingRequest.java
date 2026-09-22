package com.hify.agent.api;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import java.util.List;
public record AgentMcpBindingRequest(@NotNull @Size(max=16) List<@Valid AgentMcpBindingInput> bindings) {}
