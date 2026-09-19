package com.hify.application;

import com.hify.domain.AgentRun;
import com.hify.domain.ChildAgentTask;
import com.hify.domain.ChildTaskRecoveryAction;
import com.hify.domain.ChildTaskState;
import com.hify.domain.OutputDeliveryState;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChildAgentTaskRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence protocol for child work. This is not a worker scheduler. */
@Service
public class ChildAgentTaskService {
    private final ChildAgentTaskRepository tasks;
    private final AgentRunRepository runs;

    public ChildAgentTaskService(ChildAgentTaskRepository tasks, AgentRunRepository runs) {
        this.tasks = tasks;
        this.runs = runs;
    }

    @Transactional
    public ChildAgentTask create(String parentRunId, String childRunId,
                                 String taskDigest, boolean retrySafe) {
        requireParent(parentRunId);
        return tasks.save(new ChildAgentTask(UUID.randomUUID().toString(), parentRunId,
                childRunId, taskDigest, retrySafe, Instant.now()));
    }

    @Transactional
    public ChildAgentTask start(String taskId, String executorId) {
        ChildAgentTask task = locked(taskId);
        task.start(executorId, Instant.now());
        return tasks.save(task);
    }

    @Transactional
    public ChildAgentTask deliver(String taskId, String outputRef, String outputDigest) {
        ChildAgentTask task = locked(taskId);
        task.deliver(outputRef, outputDigest, Instant.now());
        return tasks.save(task);
    }

    @Transactional
    public ChildAgentTask fail(String taskId, String reason) {
        ChildAgentTask task = locked(taskId);
        task.fail(reason, Instant.now());
        return tasks.save(task);
    }

    @Transactional
    public ChildAgentTask cancel(String taskId) {
        ChildAgentTask task = locked(taskId);
        task.cancel(Instant.now());
        return tasks.save(task);
    }

    @Transactional
    public ClaimedOutput claim(String taskId, String parentRunId) {
        ChildAgentTask task = locked(taskId);
        if (!task.getParentRunId().equals(parentRunId)) throw new IllegalArgumentException("Parent Run mismatch");
        String token = task.getClaimToken() == null ? UUID.randomUUID().toString() : task.getClaimToken();
        task.claim(token, Instant.now());
        tasks.save(task);
        return new ClaimedOutput(task.getId(), task.getOutputRef(), task.getOutputDigest(), token,
                task.getOutputState());
    }

    @Transactional
    public ChildAgentTask consume(String taskId, String claimToken) {
        ChildAgentTask task = locked(taskId);
        AgentRun parent = requireParent(task.getParentRunId());
        if (parent.getState() != RunState.COMPLETED) {
            throw new IllegalStateException("Parent Run must be committed COMPLETED before consume");
        }
        task.consume(claimToken, Instant.now());
        return tasks.save(task);
    }

    /** Called only after the parent Run terminal CAS and assistant-message transaction has committed. */
    @Transactional
    public void acknowledgeClaimedForCompletedParent(String parentRunId) {
        AgentRun parent = requireParent(parentRunId);
        if (parent.getState() != RunState.COMPLETED) return;
        for (ChildAgentTask task : tasks.findByParentRunIdAndOutputState(
                parentRunId, OutputDeliveryState.CLAIMED)) {
            task.consume(task.getClaimToken(), Instant.now());
            tasks.save(task);
        }
    }

    @Transactional
    public List<ChildAgentTask> convergeLost(Set<String> liveExecutorIds) {
        List<ChildAgentTask> running = tasks.findByStateIn(List.of(ChildTaskState.RUNNING));
        for (ChildAgentTask task : running) {
            if (task.getExecutorId() != null && liveExecutorIds.contains(task.getExecutorId())) continue;
            AgentRun parent = requireParent(task.getParentRunId());
            ChildTaskRecoveryAction action;
            if (parent.getState() != RunState.RUNNING) action = ChildTaskRecoveryAction.RETURN_TO_USER;
            else if (task.isRetrySafe() && task.getAttemptCount() < 2) action = ChildTaskRecoveryAction.RETRY;
            else action = ChildTaskRecoveryAction.REPLAN;
            task.markLost(action, Instant.now());
            tasks.save(task);
        }
        return running;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverAfterRestart() {
        // In-memory executors do not survive this JVM. Persist loss first; a later scheduler decides the action.
        convergeLost(Set.of());
        runs.findByStateIn(List.of(RunState.COMPLETED)).forEach(run ->
                acknowledgeClaimedForCompletedParent(run.getId()));
    }

    private ChildAgentTask locked(String id) {
        return tasks.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("Child task not found"));
    }

    private AgentRun requireParent(String id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Parent Run not found"));
    }

    public record ClaimedOutput(String taskId, String outputRef, String outputDigest,
                                String claimToken, OutputDeliveryState state) {}
}
