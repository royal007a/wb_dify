package com.hify.api;

import com.hify.agent.api.AgentResponse;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentToolBindingRequest;
import com.hify.agent.api.AgentKnowledgeBindingRequest;
import com.hify.agent.api.AgentKnowledgeBindingSnapshot;
import com.hify.agent.api.AgentUpdateRequest;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentVersionResponse;
import com.hify.agent.api.AgentWorkflowBindingRequest;
import com.hify.agent.api.AgentWorkflowBindingSnapshot;
import com.hify.agent.api.AgentMcpBindingRequest;
import com.hify.agent.api.AgentMcpToolSnapshot;
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
                               @Valid @RequestBody AgentUpdateRequest request) {
        agents.update(agentId, request);
        return Result.ok();
    }

    @PutMapping("/{agentId}/tools")
    public Result<List<String>> replaceTools(@PathVariable String agentId,
                                             @Valid @RequestBody AgentToolBindingRequest request) {
        return Result.ok(agents.replaceTools(agentId, request));
    }

    @PutMapping("/{agentId}/knowledge-bindings")
    public Result<List<AgentKnowledgeBindingSnapshot>> replaceKnowledge(
            @PathVariable String agentId,
            @Valid @RequestBody AgentKnowledgeBindingRequest request) {
        return Result.ok(agents.replaceKnowledge(agentId, request));
    }

    @PutMapping("/{agentId}/workflow-binding")
    public Result<AgentWorkflowBindingSnapshot> replaceWorkflow(
            @PathVariable String agentId,
            @Valid @RequestBody AgentWorkflowBindingRequest request) {
        return Result.ok(agents.replaceWorkflow(agentId, request));
    }

    @DeleteMapping("/{agentId}/workflow-binding")
    public Result<Void> clearWorkflow(@PathVariable String agentId) {
        agents.clearWorkflow(agentId);
        return Result.ok();
    }

    @PutMapping("/{agentId}/mcp-bindings")
    public Result<List<AgentMcpToolSnapshot>> replaceMcpTools(
            @PathVariable String agentId, @Valid @RequestBody AgentMcpBindingRequest request) {
        return Result.ok(agents.replaceMcpTools(agentId, request));
    }

    @DeleteMapping("/{agentId}")
    public Result<Void> archive(@PathVariable String agentId) {
        agents.archive(agentId);
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
