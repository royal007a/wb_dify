package com.hify.runtime.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanStateMachineTest {
    @Test
    void supportsTryConfirmExecuteCheckpointFlow() {
        PlanStateMachine machine = new PlanStateMachine();

        machine.transitionTo(PlanPhase.TRYING);
        machine.transitionTo(PlanPhase.AWAITING_CONFIRMATION);
        machine.transitionTo(PlanPhase.EXECUTING);
        machine.transitionTo(PlanPhase.CHECKPOINTED);
        machine.transitionTo(PlanPhase.COMPLETED);

        assertThat(machine.phase()).isEqualTo(PlanPhase.COMPLETED);
        assertThat(machine.phase().terminal()).isTrue();
    }

    @Test
    void supportsDiagnoseAndReplanFlow() {
        PlanStateMachine machine = new PlanStateMachine();

        machine.transitionTo(PlanPhase.TRYING);
        machine.transitionTo(PlanPhase.DIAGNOSING);
        machine.transitionTo(PlanPhase.REPLANNING);
        machine.transitionTo(PlanPhase.PLANNED);

        assertThat(machine.phase()).isEqualTo(PlanPhase.PLANNED);
    }

    @Test
    void rejectsSkippingConfirmation() {
        PlanStateMachine machine = new PlanStateMachine();
        machine.transitionTo(PlanPhase.TRYING);
        machine.transitionTo(PlanPhase.AWAITING_CONFIRMATION);

        assertThatThrownBy(() -> machine.transitionTo(PlanPhase.CHECKPOINTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWAITING_CONFIRMATION -> CHECKPOINTED");
    }
}
