package com.hify.api;

import com.hify.application.ChildAgentTaskService;
import com.hify.domain.AgentRun;
import com.hify.domain.ChildAgentTask;
import com.hify.domain.ChildTaskRecoveryAction;
import com.hify.domain.ChildTaskState;
import com.hify.domain.Conversation;
import com.hify.domain.OutputDeliveryState;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChildAgentTaskRepository;
import com.hify.infra.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-child-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class ChildAgentTaskIntegrationTest {
    @Autowired ChildAgentTaskService service;
    @Autowired ChildAgentTaskRepository tasks;
    @Autowired AgentRunRepository runs;
    @Autowired ConversationRepository conversations;

    @Test
    void consumesOnlyAfterParentRunHasCommittedSuccessfully() {
        AgentRun parent = parentRun();
        ChildAgentTask task = service.create(parent.getId(), null, "task-digest", true);
        service.start(task.getId(), "worker-a");
        service.deliver(task.getId(), "artifact://child/output", "output-digest");
        ChildAgentTaskService.ClaimedOutput claimed = service.claim(task.getId(), parent.getId());

        assertThat(claimed.state()).isEqualTo(OutputDeliveryState.CLAIMED);
        assertThatThrownBy(() -> service.consume(task.getId(), claimed.claimToken()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");

        parent.finish(RunState.COMPLETED, "COMPLETED", "parent answer", 1, 0);
        runs.saveAndFlush(parent);
        service.acknowledgeClaimedForCompletedParent(parent.getId());

        assertThat(tasks.findById(task.getId()).orElseThrow().getOutputState())
                .isEqualTo(OutputDeliveryState.CONSUMED);
    }

    @Test
    void restartMarksMissingExecutorsLostAndRecordsARecoveryDecision() {
        AgentRun parent = parentRun();
        ChildAgentTask task = service.create(parent.getId(), null, "retryable", true);
        service.start(task.getId(), "dead-worker");

        service.convergeLost(Set.of("other-worker"));
        ChildAgentTask lost = tasks.findById(task.getId()).orElseThrow();
        assertThat(lost.getState()).isEqualTo(ChildTaskState.LOST);
        assertThat(lost.getRecoveryAction()).isEqualTo(ChildTaskRecoveryAction.RETRY);

        service.start(task.getId(), "dead-again");
        service.convergeLost(Set.of());
        ChildAgentTask secondLoss = tasks.findById(task.getId()).orElseThrow();
        assertThat(secondLoss.getRecoveryAction()).isEqualTo(ChildTaskRecoveryAction.REPLAN);
        assertThat(secondLoss.getAttemptCount()).isEqualTo(2);
    }

    private AgentRun parentRun() {
        String suffix = UUID.randomUUID().toString();
        Conversation conversation = new Conversation("conversation-" + suffix,
                "demo-agent", "child task test", Instant.now());
        conversations.saveAndFlush(conversation);
        AgentRun run = new AgentRun("run-" + suffix, conversation.getId(),
                "key-" + suffix, "hash-" + suffix, "parent input", Instant.now());
        return runs.saveAndFlush(run);
    }
}
