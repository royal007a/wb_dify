package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "agent_runs", uniqueConstraints =
        @UniqueConstraint(name = "uq_run_idempotency", columnNames = {"conversation_id", "idempotency_key"}))
public class AgentRun {
    @Id
    private String id;
    private String conversationId;
    private String idempotencyKey;
    private String requestHash;
    @Enumerated(EnumType.STRING)
    private RunState state;
    private String terminalReason;
    @Column(length = 20000)
    private String inputMessage;
    @Column(length = 20000)
    private String outputMessage;
    private int turns;
    private int toolCalls;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant cancelRequestedAt;
    @Version
    private long version;

    protected AgentRun() {}

    public AgentRun(String id, String conversationId, String idempotencyKey,
                    String requestHash, String inputMessage, Instant now) {
        this.id = id;
        this.conversationId = conversationId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.inputMessage = inputMessage;
        this.state = RunState.RUNNING;
        this.turns = 0;
        this.toolCalls = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void finish(RunState state, String terminalReason, String outputMessage,
                       int turns, int toolCalls) {
        if (this.state.terminal()) return;
        this.state = state;
        this.terminalReason = terminalReason;
        this.outputMessage = outputMessage;
        this.turns = turns;
        this.toolCalls = toolCalls;
        this.updatedAt = Instant.now();
    }

    public void requestCancel() {
        if (!state.terminal() && cancelRequestedAt == null) {
            cancelRequestedAt = Instant.now();
            updatedAt = cancelRequestedAt;
        }
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public RunState getState() { return state; }
    public String getTerminalReason() { return terminalReason; }
    public String getInputMessage() { return inputMessage; }
    public String getOutputMessage() { return outputMessage; }
    public int getTurns() { return turns; }
    public int getToolCalls() { return toolCalls; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCancelRequestedAt() { return cancelRequestedAt; }
    public boolean isCancelRequested() { return cancelRequestedAt != null; }
    public long getVersion() { return version; }
}
