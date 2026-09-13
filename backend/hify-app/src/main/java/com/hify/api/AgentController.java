package com.hify.api;

import com.hify.agent.api.AgentResponse;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentVersionResponse;
import com.hify.common.PageResult;
import com.hify.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {
    private final AgentService agents;

    public AgentController(AgentService agents) { this.agents = agents; }

    @GetMapping
    public PageResult<AgentResponse> list(@RequestParam(required = false) @Min(1) Integer page,
                                          @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize) {
        return agents.list(page, pageSize);
    }

    @PostMapping
    public ResponseEntity<Result<String>> create(@Valid @RequestBody AgentUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(agents.create(request)));
    }

    @GetMapping("/{agentId}")
    public Result<AgentResponse> get(@PathVariable String agentId) {
        return Result.ok(agents.get(agentId));
    }

    @PutMapping("/{agentId}")
    public Result<Void> update(@PathVariable String agentId,
                               @Valid @RequestBody AgentUpsertRequest request) {
        agents.update(agentId, request);
        return Result.ok();
    }

    @PostMapping("/{agentId}/publications")
    public Result<AgentVersionResponse> publish(@PathVariable String agentId) {
        return Result.ok(agents.publish(agentId));
    }

    @GetMapping("/{agentId}/versions")
    public Result<List<AgentVersionResponse>> versions(@PathVariable String agentId) {
        return Result.ok(agents.versions(agentId));
    }
}
