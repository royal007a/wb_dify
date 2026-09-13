package com.hify.api;

import com.hify.domain.AgentDefinition;
import com.hify.infra.AgentDefinitionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentDefinitionRepository agents;

    public AgentController(AgentDefinitionRepository agents) {
        this.agents = agents;
    }

    @GetMapping
    public List<AgentDefinition> list() {
        return agents.findAll();
    }

    @PostMapping
    public AgentDefinition create(@Valid @RequestBody CreateAgent request) {
        return agents.save(new AgentDefinition(
                UUID.randomUUID().toString(), request.name(), request.description(), request.instructions(),
                request.providerId(), request.model(), request.temperature(), request.maxTurns(),
                String.join(",", request.enabledTools()), true));
    }

    public record CreateAgent(
            @NotBlank String name,
            String description,
            @NotBlank String instructions,
            @NotBlank String providerId,
            String model,
            @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
            @Min(1) @Max(20) int maxTurns,
            List<String> enabledTools
    ) {
        public CreateAgent {
            enabledTools = enabledTools == null ? List.of() : enabledTools;
        }
    }
}

