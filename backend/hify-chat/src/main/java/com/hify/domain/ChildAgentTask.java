package com.hify.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "child_agent_tasks")
public class ChildAgentTask {
    @Id
    private String id;
    private String parentRunId;
    private String childRunId;
    private String taskDigest;
    private boolean retrySafe;
    private int attemptCount;
    private String executorId;
    @Enumerated(EnumType.STRING)
    private ChildTaskState state;
    private String outputRef;
    private String outputDigest;
    @Enumerated(EnumType.STRING)
    private OutputDeliveryState outputState;
    private String claimToken;
    @Enumerated(EnumType.STRING)
    private ChildTaskRecoveryAction recoveryAction;
    private String failureReason;
    private Instant deliveredAt;
    private Instant claimedAt;
    private Instant consumedAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private long version;

    protected ChildAgentTask() {}

    public ChildAgentTask(String id, String parentRunId, String childRunId,
                          String taskDigest, boolean retrySafe, Instant now) {
        if (id == null || id.isBlank() || parentRunId == null || parentRunId.isBlank()
                || taskDigest == null || taskDigest.isBlank()) {
            throw new IllegalArgumentException("Child task identity is required");
        }
        this.id = id;
        this.parentRunId = parentRunId;
        this.childRunId = childRunId;
        this.taskDigest = taskDigest;
        this.retrySafe = retrySafe;
        this.state = ChildTaskState.QUEUED;
        this.outputState = OutputDeliveryState.NONE;
        this.recoveryAction = ChildTaskRecoveryAction.NONE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void start(String executorId, Instant now) {
        if (state != ChildTaskState.QUEUED && state != ChildTaskState.LOST) {
            throw new IllegalStateException("Only queued or lost child tasks can start");
        }
        if (executorId == null || executorId.isBlank()) throw new IllegalArgumentException("Executor is required");
        this.executorId = executorId;
        this.state = ChildTaskState.RUNNING;
        this.recoveryAction = ChildTaskRecoveryAction.NONE;
        this.failureReason = null;
        this.attemptCount++;
        this.updatedAt = now;
    }

    public void deliver(String outputRef, String outputDigest, Instant now) {
        if (state != ChildTaskState.RUNNING) throw new IllegalStateException("Only running tasks can deliver");
        if (outputRef == null || outputRef.isBlank() || outputDigest == null || outputDigest.isBlank()) {
            throw new IllegalArgumentException("Output reference and digest are required");
        }
        this.outputRef = outputRef;
        this.outputDigest = outputDigest;
        this.state = ChildTaskState.SUCCEEDED;
        this.outputState = OutputDeliveryState.DELIVERED;
        this.deliveredAt = now;
        this.updatedAt = now;
    }

    public void claim(String token, Instant now) {
        if (state != ChildTaskState.SUCCEEDED) throw new IllegalStateException("Only successful tasks have output");
        if (outputState == OutputDeliveryState.CONSUMED) return;
        if (outputState == OutputDeliveryState.CLAIMED && !claimToken.equals(token)) {
            throw new IllegalStateException("Child output is already claimed");
        }
        if (outputState == OutputDeliveryState.DELIVERED) {
            this.claimToken = token;
            this.outputState = OutputDeliveryState.CLAIMED;
            this.claimedAt = now;
            this.updatedAt = now;
        }
    }

    public void consume(String token, Instant now) {
        if (outputState == OutputDeliveryState.CONSUMED) return;
        if (outputState != OutputDeliveryState.CLAIMED || !claimToken.equals(token)) {
            throw new IllegalStateException("A matching claim is required before consume");
        }
        this.outputState = OutputDeliveryState.CONSUMED;
        this.consumedAt = now;
        this.updatedAt = now;
    }

    public void markLost(ChildTaskRecoveryAction action, Instant now) {
        if (state != ChildTaskState.RUNNING) return;
        if (action == null || action == ChildTaskRecoveryAction.NONE) {
            throw new IllegalArgumentException("Lost tasks require an explicit recovery action");
        }
        this.state = ChildTaskState.LOST;
        this.recoveryAction = action;
        this.failureReason = "executor_lost";
        this.updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        if (state != ChildTaskState.RUNNING) throw new IllegalStateException("Only running tasks can fail");
        this.state = ChildTaskState.FAILED;
        this.failureReason = reason == null || reason.isBlank() ? "child_task_failed" : reason;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        if (state.terminal()) return;
        this.state = ChildTaskState.CANCELLED;
        this.failureReason = "cancelled";
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getParentRunId() { return parentRunId; }
    public String getChildRunId() { return childRunId; }
    public String getTaskDigest() { return taskDigest; }
    public boolean isRetrySafe() { return retrySafe; }
    public int getAttemptCount() { return attemptCount; }
    public String getExecutorId() { return executorId; }
    public ChildTaskState getState() { return state; }
    public String getOutputRef() { return outputRef; }
    public String getOutputDigest() { return outputDigest; }
    public OutputDeliveryState getOutputState() { return outputState; }
    public String getClaimToken() { return claimToken; }
    public ChildTaskRecoveryAction getRecoveryAction() { return recoveryAction; }
    public String getFailureReason() { return failureReason; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
