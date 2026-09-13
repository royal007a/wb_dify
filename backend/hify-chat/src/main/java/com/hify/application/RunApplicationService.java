package com.hify.application;

import com.hify.domain.AgentDefinition;
import com.hify.domain.AgentRun;
import com.hify.domain.ChatMessage;
import com.hify.domain.Conversation;
import com.hify.domain.ModelProvider;
import com.hify.domain.RunState;
import com.hify.infra.AgentDefinitionRepository;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.ConversationRepository;
import com.hify.infra.ModelProviderRepository;
import com.hify.runtime.ModelClient;
import com.hify.runtime.ModelClientFactory;
import com.hify.runtime.QueryLoop;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.TerminalReason;
import com.hify.runtime.ToolRuntime;
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
import java.util.Arrays;
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
    private final AgentDefinitionRepository agents;
    private final ModelProviderRepository providers;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentRunRepository runs;
    private final ModelClientFactory modelClients;
    private final QueryLoop queryLoop;
    private final RunEventBroker eventBroker;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final Duration runTimeout;
    private final int maxToolCalls;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public RunApplicationService(AgentDefinitionRepository agents, ModelProviderRepository providers,
                                 ConversationRepository conversations, ChatMessageRepository messages,
                                 AgentRunRepository runs, ModelClientFactory modelClients,
                                 QueryLoop queryLoop, RunEventBroker eventBroker,
                                 TransactionTemplate transactions,
                                 @Qualifier("runExecutor") Executor executor,
                                 @Value("${hify.run-timeout:60s}") Duration runTimeout,
                                 @Value("${hify.max-tool-calls:12}") int maxToolCalls) {
        this.agents = agents;
        this.providers = providers;
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
        this.modelClients = modelClients;
        this.queryLoop = queryLoop;
        this.eventBroker = eventBroker;
        this.transactions = transactions;
        this.executor = executor;
        this.runTimeout = runTimeout;
        this.maxToolCalls = maxToolCalls;
    }

    public CreateResult create(String conversationId, String idempotencyKey, String message) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 128 characters");
        }
        String hash = sha256(message);
        CreateResult created;
        try {
            created = transactions.execute(status -> createInTransaction(
                    conversationId, idempotencyKey, message, hash));
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
                    Map.of("version", 1, "runId", runId));
            eventBroker.publish(runId, "run.started",
                    Map.of("version", 1, "runId", runId));
            cancellations.put(runId, new AtomicBoolean(false));
            executor.execute(() -> execute(runId));
        }
        return created;
    }

    private CreateResult createInTransaction(String conversationId, String idempotencyKey,
                                             String message, String hash) {
        Conversation conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        AgentDefinition agent = agents.findById(conversation.getAgentId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + conversation.getAgentId()));
        if (!agent.isEnabled()) throw new IllegalStateException("Agent is disabled");
        providers.findById(agent.getProviderId())
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + agent.getProviderId()));

        AgentRun existing = runs.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey)
                .orElse(null);
        if (existing != null) return replay(existing, hash);

        Instant now = Instant.now();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), conversationId,
                idempotencyKey, hash, message, now);
        messages.save(new ChatMessage(conversationId, "user", message));
        conversation.touch();
        conversations.save(conversation);
        runs.saveAndFlush(run);
        return new CreateResult(run, false);
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
            cancellations.computeIfAbsent(runId, ignored -> new AtomicBoolean()).set(true);
            eventBroker.publish(runId, "run.cancel.requested",
                    Map.of("version", 1, "runId", runId));
        }
        return run;
    }

    private void execute(String runId) {
        try {
            AgentRun run = get(runId);
            Conversation conversation = conversations.findById(run.getConversationId())
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
            AgentDefinition agent = agents.findById(conversation.getAgentId())
                    .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
            ModelProvider provider = providers.findById(agent.getProviderId())
                    .orElseThrow(() -> new IllegalArgumentException("Provider not found"));

            List<RuntimeMessage> runtimeMessages = new ArrayList<>();
            runtimeMessages.add(RuntimeMessage.system(agent.getInstructions()));
            messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).forEach(message ->
                    runtimeMessages.add(new RuntimeMessage(message.getRole(), message.getContent(), null, List.of())));

            ModelClient modelClient = modelClients.create(provider);
            String model = agent.getModel() == null || agent.getModel().isBlank()
                    ? provider.getDefaultModel() : agent.getModel();
            AtomicBoolean cancelled = cancellations.computeIfAbsent(runId, ignored -> new AtomicBoolean(false));
            QueryLoop.RunPolicy policy = new QueryLoop.RunPolicy(agent.getMaxTurns(), maxToolCalls,
                    16_384, runTimeout, cancelled::get);

            QueryLoop.Result result = queryLoop.run(runtimeMessages, modelClient, model,
                    agent.getTemperature(), enabledTools(agent), policy, observer(runId));
            finish(runId, result);
        } catch (RuntimeException exception) {
            finishFailure(runId, exception);
        } finally {
            cancellations.remove(runId);
        }
    }

    private QueryLoop.RunObserver observer(String runId) {
        return new QueryLoop.RunObserver() {
            @Override
            public void onModelStarted(int turn) {
                eventBroker.publish(runId, "model.started", Map.of("version", 1, "runId", runId, "turn", turn));
            }

            @Override
            public void onModelCompleted(int turn, RuntimeMessage message) {
                eventBroker.publish(runId, "model.completed", Map.of(
                        "version", 1, "runId", runId, "turn", turn,
                        "toolCalls", message.toolCalls() == null ? 0 : message.toolCalls().size()));
                if (message.content() != null && !message.content().isBlank()) {
                    eventBroker.publish(runId, "message.delta", Map.of(
                            "version", 1, "runId", runId, "content", message.content()));
                }
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
            case MAX_TURNS, TOKEN_BUDGET_EXCEEDED, TOOL_BUDGET_EXCEEDED -> RunState.LIMIT_EXCEEDED;
            default -> RunState.FAILED;
        };
        boolean won = finishTerminal(runId, state, result.reason().name(), result.finalText(),
                result.turns(), result.toolCalls(), state == RunState.COMPLETED);
        if (!won) return;

        String type = switch (state) {
            case COMPLETED -> "run.completed";
            case CANCELLED -> "run.cancelled";
            default -> "run.failed";
        };
        eventBroker.publish(runId, type, Map.of(
                "version", 1, "runId", runId, "state", state.name(),
                "terminalReason", result.reason().name(), "turns", result.turns(),
                "toolCalls", result.toolCalls()));
    }

    private void finishFailure(String runId, RuntimeException exception) {
        boolean won = finishTerminal(runId, RunState.FAILED, TerminalReason.MODEL_ERROR.name(),
                "Run failed: " + exception.getMessage(), 0, 0, false);
        if (!won) return;
        eventBroker.publish(runId, "run.failed", Map.of(
                "version", 1, "runId", runId, "state", RunState.FAILED.name(),
                "terminalReason", TerminalReason.MODEL_ERROR.name()));
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
        runs.findByStateIn(List.of(RunState.RUNNING)).forEach(run -> finishTerminal(
                run.getId(), RunState.FAILED, "INTERRUPTED",
                "Application restarted before Run completed.",
                run.getTurns(), run.getToolCalls(), false));
    }

    private Set<String> enabledTools(AgentDefinition agent) {
        if (agent.getEnabledTools() == null || agent.getEnabledTools().isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.stream(agent.getEnabledTools().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList());
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
}
