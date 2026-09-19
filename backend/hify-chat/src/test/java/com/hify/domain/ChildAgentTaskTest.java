package com.hify.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildAgentTaskTest {
    @Test
    void enforcesDeliveredClaimedConsumedOrder() {
        ChildAgentTask task = new ChildAgentTask("task", "parent", null,
                "digest", true, Instant.now());

        assertThatThrownBy(() -> task.claim("claim", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        task.start("worker", Instant.now());
        task.deliver("artifact://answer", "output-digest", Instant.now());
        assertThat(task.getOutputState()).isEqualTo(OutputDeliveryState.DELIVERED);
        task.claim("claim", Instant.now());
        assertThat(task.getOutputState()).isEqualTo(OutputDeliveryState.CLAIMED);
        assertThatThrownBy(() -> task.consume("wrong", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        task.consume("claim", Instant.now());
        assertThat(task.getOutputState()).isEqualTo(OutputDeliveryState.CONSUMED);
    }

    @Test
    void lostIsExplicitAndDoesNotPretendTheWorkFailedOrSucceeded() {
        ChildAgentTask task = new ChildAgentTask("task", "parent", null,
                "digest", true, Instant.now());
        task.start("worker", Instant.now());
        task.markLost(ChildTaskRecoveryAction.RETRY, Instant.now());

        assertThat(task.getState()).isEqualTo(ChildTaskState.LOST);
        assertThat(task.getRecoveryAction()).isEqualTo(ChildTaskRecoveryAction.RETRY);
        assertThat(task.getOutputState()).isEqualTo(OutputDeliveryState.NONE);
    }
}
