package com.hify.runtime;

import com.hify.common.ExecutionControl;
import com.hify.runtime.plan.ExecutionCheckpoint;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.state.ExecutionContextState;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class QueryLoopParentControlTest {
    private final ToolRuntime tools=new ToolRuntime();
    private final QueryLoop loop=new QueryLoop(tools);
    private final CapabilitySnapshot capability=tools.snapshot("fixture",Set.of());
    private final QueryLoop.RunPolicy policy=new QueryLoop.RunPolicy(3,0,4096,Duration.ofMinutes(1),()->false);
    private final List<RuntimeMessage> messages=List.of(RuntimeMessage.user("question"));
    @Test void expiredParentStopsFreshLoopWithoutCreatingANewBudget() {
        var calls=new AtomicInteger();
        ModelClient client=request->{calls.incrementAndGet();return RuntimeMessage.assistant("late");};
        var result=loop.run(messages,client,"mock",0,capability,policy,QueryLoop.RunObserver.NOOP,
                RunRuntimeIdentity.local(capability),ExecutionControl.withTimeout(Duration.ofNanos(1),()->false));
        assertThat(result.reason()).isEqualTo(TerminalReason.TIMEOUT);
        assertThat(calls).hasValue(0);
    }
    @Test void expiredParentStopsCheckpointResumeWithoutCreatingANewBudget() {
        var calls=new AtomicInteger();
        ModelClient client=request->{calls.incrementAndGet();return RuntimeMessage.assistant("late");};
        var checkpoint=ExecutionCheckpoint.capture(1,0,ExecutionPlan.initial("question"),ExecutionContextState.empty(),messages);
        var result=loop.resume(checkpoint,client,"mock",0,capability,policy,QueryLoop.RunObserver.NOOP,
                RunRuntimeIdentity.local(capability),ExecutionControl.withTimeout(Duration.ofNanos(1),()->false));
        assertThat(result.reason()).isEqualTo(TerminalReason.TIMEOUT);
        assertThat(result.turns()).isEqualTo(1);
        assertThat(calls).hasValue(0);
    }
    @Test void modelReceivesParentLimitAndStillHonorsPolicyCancellation() {
        var parent=ExecutionControl.withTimeout(Duration.ofSeconds(30),()->false);
        var calls=new AtomicInteger();
        ModelClient client=request->{
            calls.incrementAndGet();
            assertThat(request.control().remaining(Duration.ofDays(1))).isLessThanOrEqualTo(Duration.ofSeconds(30));
            return RuntimeMessage.assistant("timely");
        };
        var result=loop.run(messages,client,"mock",0,capability,policy,QueryLoop.RunObserver.NOOP,
                RunRuntimeIdentity.local(capability),parent);
        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(calls).hasValue(1);
        var cancelledPolicy=new QueryLoop.RunPolicy(3,0,4096,Duration.ofMinutes(1),()->true);
        var cancelled=loop.run(messages,client,"mock",0,capability,cancelledPolicy,QueryLoop.RunObserver.NOOP,
                RunRuntimeIdentity.local(capability),ExecutionControl.none());
        assertThat(cancelled.reason()).isEqualTo(TerminalReason.CANCELLED);
        assertThat(calls).hasValue(1);
    }
}
