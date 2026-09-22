package com.hify.agent.api;
import jakarta.validation.constraints.*; import java.util.List;
public record AgentMcpBindingInput(@NotBlank @Size(max=64) String serverId,@NotEmpty @Size(max=32) List<@NotBlank @Size(max=255) String> toolNames) {}
