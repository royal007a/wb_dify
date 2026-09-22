package com.hify.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentRuntimeSnapshot;
import com.hify.domain.AgentRun;
import com.hify.domain.ChatMessage;
import com.hify.domain.Conversation;
import com.hify.domain.RunCheckpoint;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.ConversationRepository;
import com.hify.provider.api.ProviderQueryService;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.infra.RunCheckpointRepository;
import com.hify.runtime.ModelClient;
import com.hify.runtime.ModelClientFactory;
import com.hify.runtime.CapabilitySnapshot;
import com.hify.runtime.QueryLoop;
import com.hify.runtime.RunRuntimeIdentity;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.TerminalReason;
import com.hify.runtime.ToolRuntime;
import com.hify.runtime.plan.ExecutionCheckpoint;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.plan.PlanEventType;
import com.hify.runtime.plan.PlanStep;
import com.hify.runtime.plan.ReplanDecision;
import com.hify.runtime.plan.StepAttempt;
import com.hify.runtime.state.ContinuationDecision;
import com.hify.runtime.state.ExecutionContextState;
import com.hify.runtime.state.RecoveryNarrative;
import com.hify.runtime.context.ContextManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RunApplicationService {
    private final AgentQueryService agents;
    private final ProviderQueryService providers;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentRunRepository runs;
    private final ModelClientFactory modelClients;
    private final QueryLoop queryLoop;
    private final ToolRuntime toolRuntime;
    private final CommittedHistoryWriter historyWriter;
    private final RunEventBroker eventBroker;
    private final RunCheckpointRepository checkpoints;
    private final ChildAgentTaskService childTasks;
    private final KnowledgeRetrievalPort knowledge;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final Duration runTimeout;
    private final int maxToolCalls;
    private final int maxReplans;
    private final int maxRetries;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final Map<String, String> activeAttempts = new ConcurrentHashMap<>();

    public RunApplicationService(AgentQueryService agents, ProviderQueryService providers,
                                 ConversationRepository conversations, ChatMessageRepository messages,
                                 AgentRunRepository runs, ModelClientFactory modelClients,
                                 QueryLoop queryLoop, ToolRuntime toolRuntime,
                                 CommittedHistoryWriter historyWriter, RunEventBroker eventBroker,
                                 RunCheckpointRepository checkpoints, ChildAgentTaskService childTasks,
                                 KnowledgeRetrievalPort knowledge,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate transactions,
                                 @Qualifier("runExecutor") Executor executor,
                                 @Value("${hify.run-timeout:60s}") Duration runTimeout,
                                 @Value("${hify.max-tool-calls:12}") int maxToolCalls,
                                 @Value("${hify.max-replans:2}") int maxReplans,
                                 @Value("${hify.max-retries:1}") int maxRetries) {
        this.agents = agents;
        this.providers = providers;
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.modelClients = modelClients;
        this.queryLoop = queryLoop;
        this.toolRuntime = toolRuntime;
        this.historyWriter = historyWriter;
        this.eventBroker = eventBroker;
        this.checkpoints = checkpoints;
        this.childTasks = childTasks;
        this.knowledge = knowledge;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.executor = executor;
        this.runTimeout = runTimeout;
        this.maxToolCalls = maxToolCalls;
        this.maxReplans = maxReplans;
        this.maxRetries = maxRetries;
    }

    public CreateResult create(String conversationId, String idempotencyKey, String message) {
        return create(conversationId, idempotencyKey, message, null);
    }

    public CreateResult create(String conversationId, String idempotencyKey, String message,
                               ResumeRequest resume) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 128 characters");
        }
        String resumeKey = resume == null ? "" : "\n" + resume.runId() + "\n"
                + resume.gapIds().stream().sorted().toList();
        String hash = sha256(message + resumeKey);
        CreateResult created;
        try {
            created = transactions.execute(status -> createInTransaction(
                    conversationId, idempotencyKey, message, hash, resume));
        } catch (DataIntegrityViolationException conflict) {
            // A concurrent request may pass the initial lookup before the winner commits.
            // The database unique constraint is the arbiter; the losing transaction rolls
            // back its user message and resolves to the already-created Run.
            created = replayExisting(conversationId, idempotencyKey, hash);
        }

        if (created == null) throw new IllegalStateException("Could not create Run");
        if (!created.replayed()) {
            String runId = created.run().getId();
            eventBroker.publish(runId, "run.created",
                    Map.of("version", 1, "runId", runId,
                            "capabilityRevision", created.run().getCapabilityRevision(),
                            "toolSchemaDigest", created.run().getToolSchemaDigest()));
            eventBroker.publish(runId, "run.started",
                    Map.of("version", 1, "runId", runId));
            if (created.run().getResumedFromRunId() != null) {
                eventBroker.publish(runId, "run.input.accepted", Map.of(
                        "version", 1, "runId", runId,
                        "resumedFromRunId", created.run().getResumedFromRunId(),
                        "resolvedGapIds", resume == null ? List.of() : resume.gapIds()));
            }
            cancellations.put(runId, new AtomicBoolean(false));
            executor.execute(() -> execute(runId));
        }
        return created;
    }

    private CreateResult createInTransaction(String conversationId, String idempotencyKey,
                                             String message, String hash, ResumeRequest resume) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        AgentRuntimeSnapshot agent = conversation.getAgentVersionId() == null
                ? agents.requirePublished(conversation.getAgentId())
                : agents.requireVersion(conversation.getAgentVersionId());
        providers.requireEnabled(agent.providerId());

        AgentRun existing = runs.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey)
                .orElse(null);
        if (existing != null) return replay(existing, hash);

        Instant now = Instant.now();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), conversationId,
                idempotencyKey, hash, message,
                resume == null ? null : resume.runId(),
                resume == null ? null : String.join(",", resume.gapIds()), now);
        run.bindAgentSnapshot(agent.versionId(), agent.snapshotDigest());
        CapabilitySnapshot capability = toolRuntime.snapshot(agent.versionId(), enabledTools(agent));
        run.bindCapabilitySnapshot(capability.revision(), capability.toolSchemaDigest());
        messages.save(new ChatMessage(conversationId, "user", message));
        conversation.touch();
        conversations.save(conversation);
        runs.saveAndFlush(run);
        if (resume != null) copyResolvedCheckpoint(conversationId, run.getId(), message, resume);
        return new CreateResult(run, false);
    }

    private void copyResolvedCheckpoint(String conversationId, String newRunId, String input,
                                        ResumeRequest resume) {
        AgentRun source = runs.findById(resume.runId())
                .orElseThrow(() -> new IllegalArgumentException("Resume Run not found: " + resume.runId()));
        if (!source.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("Resume Run belongs to another conversation");
        }
        if (source.getState() != RunState.NEEDS_INPUT) {
            throw new IllegalStateException("Resume Run is not waiting for input");
        }
        ExecutionCheckpoint sourceCheckpoint = restoreCheckpoint(source.getId())
                .orElseThrow(() -> new IllegalStateException("Resume Run has no restorable checkpoint"));
        ExecutionContextState resolved = sourceCheckpoint.contextState()
                .resolveGapsWithUserInput(resume.gapIds(), input,
                        sourceCheckpoint.planVersion(), newRunId);
        List<RuntimeMessage> resumedMessages = new ArrayList<>(sourceCheckpoint.messages());
        resumedMessages.add(RuntimeMessage.user(input));
        ExecutionCheckpoint copied = new ExecutionCheckpoint(UUID.randomUUID().toString(),
                sourceCheckpoint.turn(), sourceCheckpoint.toolCalls(), sourceCheckpoint.plan(), resolved,
                resumedMessages, true, Instant.now());
        persistCheckpoint(newRunId, copied);
    }

    private CreateResult replayExisting(String conversationId, String idempotencyKey, String hash) {
        AgentRun existing = runs.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Concurrent Run creation did not converge"));
        return replay(existing, hash);
    }

    private CreateResult replay(AgentRun existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new IllegalStateException("IDEMPOTENCY_KEY_REUSED");
        }
        return new CreateResult(existing, true);
    }

    public AgentRun get(String runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public AgentRun cancel(String runId) {
        AgentRun run = get(runId);
        if (!run.getState().terminal()) {
            int updated = runs.requestCancel(runId, RunState.RUNNING, Instant.now());
            if (updated == 1) {
                cancellations.computeIfAbsent(runId, ignored -> new AtomicBoolean()).set(true);
                eventBroker.publish(runId, "run.cancel.requested",
                        Map.of("version", 1, "runId", runId));
            }
        }
        return get(runId);
    }

    private void execute(String runId) {
        try {
            AgentRun run = get(runId);
            Conversation conversation = conversations.findById(run.getConversationId())
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
            AgentRuntimeSnapshot agent = run.getAgentVersionId() == null
                    ? agents.requirePublished(conversation.getAgentId())
                    : agents.requireVersion(run.getAgentVersionId());
            ProviderRuntimeConfig provider = providers.requireEnabled(agent.providerId());
            CapabilitySnapshot capability = toolRuntime.snapshot(agent.versionId(), enabledTools(agent));
            if (run.getCapabilityRevision() == null || run.getToolSchemaDigest() == null) {
                run.bindCapabilitySnapshot(capability.revision(), capability.toolSchemaDigest());
                runs.saveAndFlush(run);
            } else if (!run.getCapabilityRevision().equals(capability.revision())
                    || !run.getToolSchemaDigest().equals(capability.toolSchemaDigest())) {
                throw new CapabilityMismatchException(runId);
            }

            List<RuntimeMessage> runtimeMessages = new ArrayList<>();
            runtimeMessages.add(RuntimeMessage.system(agent.instructions()));
            String knowledgeContext=knowledgeContext(runId,run.getInputMessage(),agent);
            if(!knowledgeContext.isBlank())runtimeMessages.add(RuntimeMessage.system(knowledgeContext));
            messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).forEach(message ->
                    runtimeMessages.add(new RuntimeMessage(message.getRole(), message.getContent(), null, List.of())));

            ModelClient modelClient = modelClients.create(provider);
            String model = agent.modelId() == null || agent.modelId().isBlank()
                    ? provider.defaultModelId() : agent.modelId();
            AtomicBoolean cancelled = cancellations.computeIfAbsent(runId, ignored -> new AtomicBoolean(false));
            QueryLoop.RunPolicy policy = new QueryLoop.RunPolicy(agent.maxTurns(), maxToolCalls,
                    16_384, maxReplans, maxRetries, runTimeout, cancelled::get);

            QueryLoop.RunObserver observer = observer(runId);
            RunRuntimeIdentity identity = new RunRuntimeIdentity(runId, capability.revision(),
                    capability.toolSchemaDigest(),
                    (attemptId, revision) -> executionLeaseActive(runId, attemptId, revision),
                    historyWriter.forRun(runId));
            QueryLoop.Result result = restoreCheckpoint(runId)
                    .map(checkpoint -> queryLoop.resume(checkpoint, modelClient, model,
                            agent.temperature(), capability, policy, observer, identity))
                    .orElseGet(() -> queryLoop.run(runtimeMessages, modelClient, model,
                            agent.temperature(), capability, policy, observer, identity));
            finish(runId, result);
        } catch (RuntimeException exception) {
            finishFailure(runId, exception);
        } finally {
            cancellations.remove(runId);
            activeAttempts.remove(runId);
        }
    }

    private QueryLoop.RunObserver observer(String runId) {
        return new QueryLoop.RunObserver() {
            @Override
            public void onPlanCreated(ExecutionPlan plan) {
                eventBroker.publish(runId, PlanEventType.PLAN_CREATED, Map.of(
                        "version", 1, "runId", runId, "planId", plan.id(),
                        "planVersion", plan.version(), "digest", plan.digest(),
                        "supersedesPlanId", plan.supersedesPlanId() == null ? "" : plan.supersedesPlanId()));
            }

            @Override
            public void onTryStarted(int turn, ExecutionPlan plan, PlanStep step, StepAttempt attempt,
                                     RuntimeMessage.ToolCall call) {
                activeAttempts.put(runId, attempt.id());
                eventBroker.publish(runId, PlanEventType.TRY_STARTED, Map.of(
                        "version", 1, "runId", runId, "planVersion", plan.version(),
                        "stepId", step.id(), "attemptId", attempt.id(),
                        "toolCallId", call.id(), "tool", call.name(), "turn", turn));
                QueryLoop.RunObserver.super.onTryStarted(turn, plan, step, attempt, call);
            }

            @Override
            public void onTryCompleted(int turn, ExecutionPlan plan, PlanStep step, StepAttempt attempt,
                                       RuntimeMessage.ToolCall call, ToolRuntime.ExecutionResult result) {
                eventBroker.publish(runId, result.error() ? PlanEventType.TRY_FAILED : PlanEventType.TRY_COMPLETED,
                        Map.of("version", 1, "runId", runId, "planVersion", plan.version(),
                                "stepId", step.id(), "attemptId", attempt.id(),
                                "toolCallId", call.id(), "tool", call.name(),
                                "failureClass", result.failureType().name(), "turn", turn));
                QueryLoop.RunObserver.super.onTryCompleted(turn, plan, step, attempt, call, result);
                activeAttempts.remove(runId, attempt.id());
            }

            @Override
            public void onReplanDecided(ExecutionPlan plan, ReplanDecision decision) {
                eventBroker.publish(runId, PlanEventType.REPLAN_DECIDED, Map.of(
                        "version", 1, "runId", runId, "planVersion", plan.version(),
                        "action", decision.action().name(), "failurePointId", decision.failurePointId(),
                        "rootCausePointId", decision.rootCausePointId(),
                        "rollbackPointId", decision.rollbackPointId(),
                        "replanFromStepId", decision.replanFromStepId(), "reason", decision.reason()));
            }

            @Override
            public void onContinuationDecided(ContinuationDecision decision) {
                eventBroker.publish(runId, "continuation.decided", Map.of(
                        "version", 1, "runId", runId, "action", decision.action().name(),
                        "reason", decision.reason(), "planVersion", decision.planVersion(),
                        "attemptId", decision.attemptId() == null ? "" : decision.attemptId(),
                        "evidenceIds", decision.evidenceIds(), "gapIds", decision.gapIds()));
            }

            @Override
            public void onContextStateChanged(ExecutionContextState contextState) {
                List<Map<String, Object>> openGaps = contextState.openBlockingGaps().stream()
                        .map(gap -> Map.<String, Object>of(
                                "id", gap.id(), "kind", gap.kind().name(),
                                "description", gap.description(),
                                "resolutionKey", gap.resolutionKey(),
                                "acquisitionOptions", gap.acquisitionOptions()))
                        .toList();
                eventBroker.publish(runId, "context.state.updated", Map.of(
                        "version", 1, "runId", runId,
                        "evidenceVersion", contextState.evidenceVersion(),
                        "gapVersion", contextState.gapVersion(),
                        "claimCount", contextState.claims().size(),
                        "evidenceCount", contextState.evidence().size(),
                        "openGapCount", openGaps.size(), "openGaps", openGaps));
            }

            @Override
            public void onContextPrepared(int turn, ContextManager.PreparedContext context) {
                if (!context.archived() && !context.compacted() && !context.layeredMemory()) return;
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("version", 1); payload.put("runId", runId); payload.put("turn", turn);
                payload.put("originalTokens", context.originalTokens());
                payload.put("preparedTokens", context.preparedTokens());
                payload.put("archivedToolResults", context.archiveReferences().size());
                payload.put("compacted", context.compacted());
                payload.put("layeredMemory", context.layeredMemory());
                payload.put("summaryId", context.summaryId());
                payload.put("catalogRefCount", context.catalogRefs().size());
                payload.put("retainedFromIndex", context.retainedFromIndex());
                eventBroker.publish(runId, "context.prepared", payload);
            }

            @Override
            public void onRecoveryNarrated(RecoveryNarrative narrative) {
                eventBroker.publish(runId, "recovery.narrated", Map.of(
                        "version", 1, "runId", runId,
                        "failurePointId", narrative.failurePointId(),
                        "rootCausePointId", narrative.rootCausePointId(),
                        "rollbackPointId", narrative.rollbackPointId(),
                        "replanFromStepId", narrative.replanFromStepId(),
                        "decision", narrative.decision().name(), "reason", narrative.reason(),
                        "evidenceIds", narrative.evidenceIds(), "gapIds", narrative.gapIds()));
            }

            @Override
            public void onConfirmationRequired(ExecutionPlan plan, PlanStep step, ReplanDecision decision) {
                eventBroker.publish(runId, PlanEventType.CONFIRMATION_REQUIRED, Map.of(
                        "version", 1, "runId", runId, "planId", plan.id(),
                        "planVersion", plan.version(), "planDigest", plan.digest(),
                        "stepId", step.id(), "reason", decision.reason()));
            }

            @Override
            public void onCheckpointCreated(ExecutionCheckpoint checkpoint) {
                persistCheckpoint(runId, checkpoint);
                eventBroker.publish(runId, PlanEventType.CHECKPOINT_CREATED, Map.of(
                        "version", 1, "runId", runId, "checkpointId", checkpoint.id(),
                        "turn", checkpoint.turn(), "toolCalls", checkpoint.toolCalls(),
                        "planId", checkpoint.planId(), "planVersion", checkpoint.planVersion(),
                        "evidenceVersion", checkpoint.evidenceVersion(),
                        "gapVersion", checkpoint.gapVersion()));
            }

            @Override
            public void onCheckpointRestored(ExecutionCheckpoint checkpoint) {
                eventBroker.publish(runId, PlanEventType.CHECKPOINT_RESTORED, Map.of(
                        "version", 1, "runId", runId, "checkpointId", checkpoint.id(),
                        "turn", checkpoint.turn(), "planVersion", checkpoint.planVersion(),
                        "evidenceVersion", checkpoint.evidenceVersion(),
                        "gapVersion", checkpoint.gapVersion()));
            }

            @Override
            public void onModelStarted(int turn) {
                eventBroker.publish(runId, "model.started", Map.of("version", 1, "runId", runId, "turn", turn));
            }

            @Override
            public void onModelDelta(int turn, String delta) {
                if (delta == null || delta.isEmpty()) return;
                eventBroker.publish(runId, "message.delta", Map.of(
                        "version", 1, "runId", runId, "turn", turn, "content", delta));
            }

            @Override
            public void onModelCompleted(int turn, RuntimeMessage message) {
                eventBroker.publish(runId, "model.completed", Map.of(
                        "version", 1, "runId", runId, "turn", turn,
                        "toolCalls", message.toolCalls() == null ? 0 : message.toolCalls().size()));
            }

            @Override
            public void onToolStarted(int turn, RuntimeMessage.ToolCall call) {
                eventBroker.publish(runId, "tool.call.started", Map.of(
                        "version", 1, "runId", runId, "turn", turn,
                        "toolCallId", call.id(), "tool", call.name()));
            }

            @Override
            public void onToolCompleted(int turn, RuntimeMessage.ToolCall call,
                                        ToolRuntime.ExecutionResult result) {
                eventBroker.publish(runId, result.error() ? "tool.call.failed" : "tool.call.completed", Map.of(
                        "version", 1, "runId", runId, "turn", turn,
                        "toolCallId", call.id(), "tool", call.name(),
                        "recoverable", !result.fatal()));
            }
        };
    }

    private void finish(String runId, QueryLoop.Result result) {
        RunState state = switch (result.reason()) {
            case COMPLETED -> RunState.COMPLETED;
            case CANCELLED -> RunState.CANCELLED;
            case TIMEOUT -> RunState.TIMED_OUT;
            case HUMAN_INPUT_REQUIRED -> RunState.NEEDS_INPUT;
            case MAX_TURNS, TOKEN_BUDGET_EXCEEDED, TOOL_BUDGET_EXCEEDED -> RunState.LIMIT_EXCEEDED;
            default -> RunState.FAILED;
        };
        boolean won = finishTerminal(runId, state, result.reason().name(), result.finalText(),
                result.turns(), result.toolCalls(), state == RunState.COMPLETED);
        if (!won) return;
        if (state == RunState.COMPLETED) childTasks.acknowledgeClaimedForCompletedParent(runId);

        String type = switch (state) {
            case COMPLETED -> "run.completed";
            case CANCELLED -> "run.cancelled";
            case NEEDS_INPUT -> "run.needs_input";
            default -> "run.failed";
        };
        eventBroker.publish(runId, type, Map.of(
                "version", 1, "runId", runId, "state", state.name(),
                "terminalReason", result.reason().name(), "turns", result.turns(),
                "toolCalls", result.toolCalls(),
                "decision", result.finalDecision().action().name(),
                "decisionReason", result.finalDecision().reason(),
                "evidenceIds", result.finalDecision().evidenceIds(),
                "gapIds", result.finalDecision().gapIds()));
    }

    private void finishFailure(String runId, RuntimeException exception) {
        TerminalReason reason = exception instanceof CapabilityMismatchException
                ? TerminalReason.CAPABILITY_MISMATCH
                : exception instanceof HistoryOperationConflictException
                ? TerminalReason.HISTORY_COMMIT_FAILED : TerminalReason.MODEL_ERROR;
        boolean won = finishTerminal(runId, RunState.FAILED, reason.name(),
                "Run failed: " + exception.getMessage(), 0, 0, false);
        if (!won) return;
        eventBroker.publish(runId, "run.failed", Map.of(
                "version", 1, "runId", runId, "state", RunState.FAILED.name(),
                "terminalReason", reason.name()));
    }

    private boolean finishTerminal(String runId, RunState state, String terminalReason,
                                   String outputMessage, int turns, int toolCalls,
                                   boolean persistAssistantMessage) {
        Boolean won = transactions.execute(status -> {
            AgentRun current = get(runId);
            int updated = runs.finishTerminal(runId, RunState.RUNNING, current.getVersion(), state,
                    terminalReason, outputMessage, turns, toolCalls, Instant.now());
            if (updated == 1 && persistAssistantMessage && outputMessage != null) {
                messages.save(new ChatMessage(current.getConversationId(), "assistant", outputMessage));
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(won);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void convergeInterruptedRuns() {
        runs.findByStateIn(List.of(RunState.RUNNING)).forEach(run -> {
            if (run.isCancelRequested()) {
                finishTerminal(run.getId(), RunState.CANCELLED, "CANCELLED_DURING_RESTART",
                        "Run was cancelled before restart recovery.",
                        run.getTurns(), run.getToolCalls(), false);
                return;
            }
            cancellations.put(run.getId(), new AtomicBoolean(false));
            executor.execute(() -> execute(run.getId()));
        });
    }

    private void persistCheckpoint(String runId, ExecutionCheckpoint checkpoint) {
        long sequence = checkpoints.findTopByRunIdOrderBySequenceNoDesc(runId)
                .map(existing -> existing.getSequenceNo() + 1).orElse(1L);
        checkpoints.saveAndFlush(new RunCheckpoint(runId, sequence, checkpoint.id(), checkpoint.turn(),
                checkpoint.toolCalls(), checkpoint.planId(), checkpoint.planVersion(),
                checkpoint.planDigest(), writePlan(checkpoint.plan()),
                checkpoint.evidenceVersion(), checkpoint.gapVersion(),
                writeContext(checkpoint.contextState()), writeMessages(checkpoint.messages()),
                checkpoint.restorable(), checkpoint.createdAt()));
    }

    private java.util.Optional<ExecutionCheckpoint> restoreCheckpoint(String runId) {
        return checkpoints.findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(runId)
                .map(saved -> new ExecutionCheckpoint(saved.getCheckpointId(), saved.getTurnNo(),
                        saved.getToolCalls(), readPlan(saved.getPlanJson()),
                        readContext(saved.getContextJson()),
                        readMessages(saved.getMessagesJson()), saved.isRestorable(), saved.getCreatedAt()));
    }

    private String writeContext(ExecutionContextState value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Run context state", exception);
        }
    }

    private ExecutionContextState readContext(String value) {
        try {
            return objectMapper.readValue(value, ExecutionContextState.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not restore Run context state", exception);
        }
    }

    private String writePlan(ExecutionPlan value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Run plan", exception);
        }
    }

    private ExecutionPlan readPlan(String value) {
        try {
            return objectMapper.readValue(value, ExecutionPlan.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not restore Run plan", exception);
        }
    }

    private String writeMessages(List<RuntimeMessage> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Run checkpoint", exception);
        }
    }

    private List<RuntimeMessage> readMessages(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Could not restore Run checkpoint", exception);
        }
    }

    private Set<String> enabledTools(AgentRuntimeSnapshot agent) {
        return new LinkedHashSet<>(agent.enabledTools());
    }

    private String knowledgeContext(String runId,String query,AgentRuntimeSnapshot agent){
        if(agent.knowledgeBindings()==null||agent.knowledgeBindings().isEmpty())return "";
        eventBroker.publish(runId,"knowledge.retrieval.started",Map.of(
                "version",1,"runId",runId,"bindingCount",agent.knowledgeBindings().size()));
        List<KnowledgeCitation> citations=new ArrayList<>();
        List<Map<String,Object>> failures=new ArrayList<>();
        agent.knowledgeBindings().stream()
                .sorted(java.util.Comparator.comparingInt(com.hify.agent.api.AgentKnowledgeBindingSnapshot::priority))
                .forEach(binding->{
                    try{
                        citations.addAll(knowledge.searchRevision(binding.corpusVersionId(),query,binding.topK()));
                    }catch(RuntimeException failure){
                        failures.add(Map.of("knowledgeBaseId",binding.knowledgeBaseId(),
                                "corpusVersionId",binding.corpusVersionId(),"reason",safeMessage(failure)));
                    }
                });
        if(!failures.isEmpty())eventBroker.publish(runId,"knowledge.retrieval.failed",Map.of(
                "version",1,"runId",runId,"recoverable",true,"failures",failures));
        List<Map<String,Object>> refs=citations.stream().map(citation->Map.<String,Object>of(
                "chunkId",citation.chunkId(),"documentId",citation.documentId(),
                "documentVersion",citation.documentVersion(),"digest",citation.digest(),
                "rank",citation.rank())).toList();
        eventBroker.publish(runId,"knowledge.retrieval.completed",Map.of(
                "version",1,"runId",runId,"citationCount",citations.size(),"citations",refs));
        if(citations.isEmpty())return "";
        StringBuilder context=new StringBuilder("KNOWLEDGE_CONTEXT\nUse only when relevant. Cite sources as [K1], [K2], ...; these references point to canonical immutable chunks.\n");
        for(int index=0;index<citations.size();index++){
            KnowledgeCitation citation=citations.get(index);
            context.append("[K").append(index+1).append("] chunkId=").append(citation.chunkId())
                    .append(" digest=").append(citation.digest()).append(" documentId=")
                    .append(citation.documentId()).append("\n").append(citation.content()).append("\n");
        }
        return context.toString();
    }

    private String safeMessage(RuntimeException failure){
        String message=failure.getMessage();
        return message==null||message.isBlank()?failure.getClass().getSimpleName():message;
    }

    private boolean executionLeaseActive(String runId, String attemptId, String capabilityRevision) {
        if (!attemptId.equals(activeAttempts.get(runId))) return false;
        return runs.findById(runId).map(run -> run.getState() == RunState.RUNNING
                && run.getCancelRequestedAt() == null
                && capabilityRevision.equals(run.getCapabilityRevision())).orElse(false);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash request", exception);
        }
    }

    public record CreateResult(AgentRun run, boolean replayed) {}
    public record ResumeRequest(String runId, List<String> gapIds) {
        public ResumeRequest {
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Resume Run id is required");
            gapIds = gapIds == null ? List.of() : gapIds.stream().distinct().toList();
            if (gapIds.isEmpty()) throw new IllegalArgumentException("At least one gap id is required");
            if (gapIds.size() > 50) throw new IllegalArgumentException("At most 50 gap ids may be resolved at once");
        }
    }
}
