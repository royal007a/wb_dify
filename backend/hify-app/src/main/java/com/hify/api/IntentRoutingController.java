package com.hify.api;

import com.hify.application.IntentRoutingApplicationService;
import com.hify.intent.IntentDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/intent-decisions")
public class IntentRoutingController {
    private final IntentRoutingApplicationService routing;

    public IntentRoutingController(IntentRoutingApplicationService routing) {
        this.routing = routing;
    }

    @PostMapping
    public IntentDecision decide(@Valid @RequestBody DecideIntent request) {
        return routing.decide(request.agentId(), request.input());
    }

    public record DecideIntent(
            @NotBlank @Size(max = 128) String agentId,
            @NotBlank @Size(max = 4000) String input
    ) {}
}
