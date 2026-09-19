package com.hify.api;

import com.hify.application.RunApplicationService;
import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentRuntimeSnapshot;
import com.hify.application.RunEventBroker;
import com.hify.domain.AgentRun;
import com.hify.domain.Conversation;
import com.hify.domain.RunEvent;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ConversationRepository;
import com.hify.infra.RunEventRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RunController {
    private final ConversationRepository conversations;
    private final AgentQueryService agents;
    private final AgentRunRepository runs;
    private final RunEventRepository events;
    private final RunApplicationService runService;
    private final RunEventBroker eventBroker;

    public RunController(ConversationRepository conversations, AgentQueryService agents,
                         AgentRunRepository runs, RunEventRepository events,
                         RunApplicationService runService, RunEventBroker eventBroker) {
        this.conversations = conversations;
        this.agents = agents;
        this.runs = runs;
        this.events = events;
        this.runService = runService;
        this.eventBroker = eventBroker;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation createConversation(@Valid @RequestBody CreateConversation request) {
        AgentRuntimeSnapshot version = agents.requirePublished(request.agentId());
        Instant now = Instant.now();
        String title = request.title() == null || request.title().isBlank()
                ? "New conversation" : request.title();
        return conversations.save(new Conversation(UUID.randomUUID().toString(), request.agentId(),
                version.versionId(), title, now));
    }

    @PostMapping("/conversations/{conversationId}/runs")
    public ResponseEntity<RunView> createRun(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRun request) {
        RunApplicationService.ResumeRequest resume = request.resume() == null ? null
                : new RunApplicationService.ResumeRequest(request.resume().runId(), request.resume().gapIds());
        RunApplicationService.CreateResult result = runService.create(
                conversationId, idempotencyKey, request.message(), resume);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(RunView.from(result.run()));
    }

    @GetMapping("/runs/{runId}")
    public RunView getRun(@PathVariable String runId) {
        return RunView.from(runService.get(runId));
    }

    @PostMapping("/runs/{runId}/cancellations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView cancel(@PathVariable String runId) {
        runService.cancel(runId);
        return RunView.from(runService.get(runId));
    }

    @GetMapping("/runs/{runId}/events")
    public List<EventView> events(@PathVariable String runId) {
        if (!runs.existsById(runId)) throw new IllegalArgumentException("Run not found: " + runId);
        return events.findByRunIdOrderByIdAsc(runId).stream().map(EventView::from).toList();
    }

    @GetMapping(value = "/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return eventBroker.subscribe(runId, lastEventId);
    }

    public record CreateConversation(@NotBlank String agentId, String title) {}
    public record CreateRun(@NotBlank String message, @Valid ResumeInput resume) {}
    public record ResumeInput(@NotBlank String runId, List<@NotBlank String> gapIds) {}

    public record RunView(String id, String conversationId, String state, String terminalReason,
                          String inputMessage, String outputMessage, int turns, int toolCalls,
                          Instant createdAt, Instant updatedAt, Instant cancelRequestedAt,
                          String resumedFromRunId, List<String> resolvedGapIds,
                          String agentVersionId, String agentSnapshotDigest,
                          String capabilityRevision, String toolSchemaDigest,
                          String streamUrl) {
        static RunView from(AgentRun run) {
            return new RunView(run.getId(), run.getConversationId(), run.getState().name(),
                    run.getTerminalReason(), run.getInputMessage(), run.getOutputMessage(),
                    run.getTurns(), run.getToolCalls(), run.getCreatedAt(), run.getUpdatedAt(),
                    run.getCancelRequestedAt(), run.getResumedFromRunId(), parseGapIds(run.getResolvedGapIds()),
                    run.getAgentVersionId(), run.getAgentSnapshotDigest(),
                    run.getCapabilityRevision(), run.getToolSchemaDigest(),
                    "/api/v1/runs/" + run.getId() + "/events/stream");
        }

        private static List<String> parseGapIds(String value) {
            return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
        }
    }

    public record EventView(Long id, long sequence, String type, String payload, Instant createdAt) {
        static EventView from(RunEvent event) {
            return new EventView(event.getId(), event.getSequenceNo(), event.getEventType(),
                    event.getPayload(), event.getCreatedAt());
        }
    }
}
