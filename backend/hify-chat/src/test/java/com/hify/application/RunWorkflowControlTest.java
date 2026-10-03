package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.*;
import com.hify.domain.*;
import com.hify.infra.*;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.provider.api.ProviderQueryService;
import com.hify.runtime.*;
import com.hify.workflow.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Drives the public restart/dispatch entry, not private terminal helpers. */
class RunWorkflowControlTest {
    private final AgentQueryService agents = mock(AgentQueryService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final WorkflowCapabilityPort workflows = mock(WorkflowCapabilityPort.class);
    private final RunEventBroker events = mock(RunEventBroker.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final AgentRun run = new AgentRun("run", "conversation", "key", "hash", "input", Instant.now());
    private final com.hify.common.ExecutionLifecycle lifecycle=mock(com.hify.common.ExecutionLifecycle.class);
    private final java.util.concurrent.Executor executor=mock(java.util.concurrent.Executor.class);
    private final RunApplicationService service = new RunApplicationService(agents, mock(ProviderQueryService.class),
            conversations, messages, runs, mock(ModelClientFactory.class), mock(QueryLoop.class), mock(ToolRuntime.class),
            mock(CommittedHistoryWriter.class), events, mock(RunCheckpointRepository.class), mock(ChildAgentTaskService.class),
            mock(KnowledgeRetrievalPort.class), workflows, new ObjectMapper(), tx, executor, Duration.ofMinutes(1), 12, 2, 1, lifecycle);

    @SuppressWarnings("unchecked")
    private void prepare(AgentRun selected) {
        doAnswer(invocation->{((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        selected.bindAgentSnapshot("av1", "digest");
        when(runs.findByStateIn(any())).thenReturn(List.of(selected));
        when(runs.findById("run")).thenReturn(Optional.of(selected));
        when(runs.findByIdForUpdate("run")).thenReturn(Optional.of(selected));
        when(conversations.findById("conversation")).thenReturn(Optional.of(new Conversation("conversation","agent","av1","title",Instant.now())));
        when(agents.requireVersion("av1")).thenReturn(new AgentRuntimeSnapshot("av1","agent",1,"digest","agent","instruction",
                "mock","mock",0.2,2048,6,10,List.of(),List.of(),new AgentWorkflowBindingSnapshot("w1","wv1",1,"checksum"),List.of(),true));
        when(tx.execute(any())).thenAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(runs.finishTerminal(anyString(),any(),anyLong(),any(),anyString(),nullable(String.class),anyInt(),anyInt(),any()))
                .thenAnswer(invocation -> { selected.finish(invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5),invocation.getArgument(6),invocation.getArgument(7));return 1; });
    }
    @Test void rejectionRacingWithShutdownLeavesRunRecoverable() {
        prepare(run);
        doAnswer(invocation->{when(lifecycle.isStopping()).thenReturn(true);throw new java.util.concurrent.RejectedExecutionException("closed");})
                .when(executor).execute(any());
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.RUNNING);
        verify(runs,never()).finishTerminal(anyString(),any(),anyLong(),any(),anyString(),nullable(String.class),anyInt(),anyInt(),any());
        verify(events,never()).publish(anyString(),eq("run.failed"),anyMap());
        assertThat((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"dispatchOwners")).isEmpty();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void recoveryDatabaseFailureForOneRunDoesNotPreventTheNextOne(boolean rejectionSettlementFails) {
        prepare(run);
        AgentRun broken=new AgentRun("broken","conversation","key2","hash","input",Instant.now());
        when(runs.findByStateIn(any())).thenReturn(List.of(broken,run));
        if(rejectionSettlementFails){
            when(runs.findById("broken")).thenReturn(Optional.of(broken));
            when(runs.findByIdForUpdate("broken")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
            var first=new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(invocation->{if(first.getAndSet(false))throw new java.util.concurrent.RejectedExecutionException("capacity");
                ((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        }else when(runs.findById("broken")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        when(workflows.execute(eq("wv1"),eq("input"),any())).thenReturn(result("SUCCEEDED"));
        assertThatCode(service::convergeInterruptedRuns).doesNotThrowAnyException();
        assertThat(run.getState()).isEqualTo(RunState.COMPLETED);
        assertThat((Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,"dispatchOwners")).isEmpty();
    }
    @Test void cancelledWorkflowIsNotMisreportedAsModelFailure() {
        prepare(run); when(workflows.execute(eq("wv1"),eq("input"),any())).thenReturn(result("CANCELLED"));
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.CANCELLED);
        verify(messages,never()).save(any());
        verify(events).publish(eq("run"),eq("run.cancelled"),anyMap());
    }
    @Test void timedOutWorkflowHasTimeoutTerminalReason() {
        prepare(run); when(workflows.execute(eq("wv1"),eq("input"),any())).thenReturn(result("TIMED_OUT"));
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.TIMED_OUT);
        assertThat(run.getTerminalReason()).isEqualTo("TIMEOUT");
    }
    @Test void persistentCancellationBeforeCommitWinsOverSuccessfulWorkflowResult() {
        prepare(run);
        when(workflows.execute(eq("wv1"),eq("input"),any())).thenAnswer(invocation -> { run.requestCancel();return result("SUCCEEDED"); });
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.CANCELLED);
        verify(messages,never()).save(any());
        verify(events,never()).publish(eq("run"),eq("run.completed"),anyMap());
    }
    @Test void restartedWorkflowDoesNotResetExpiredRunBudget() {
        var expired = new AgentRun("run","conversation","key","hash","input",Instant.now().minusSeconds(120));
        prepare(expired);
        service.convergeInterruptedRuns();
        assertThat(expired.getState()).isEqualTo(RunState.TIMED_OUT);
        verifyNoInteractions(workflows);
    }
    @Test @SuppressWarnings("unchecked") void dispatchPreservesCancellationFlagSetBeforeSubmission() {
        prepare(run);
        var flags=(Map<String,java.util.concurrent.atomic.AtomicBoolean>)org.springframework.test.util.ReflectionTestUtils.getField(service,"cancellations");
        flags.put("run",new java.util.concurrent.atomic.AtomicBoolean(true));
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.CANCELLED);
        verifyNoInteractions(workflows);
        assertThat(flags).doesNotContainKey("run");
    }
    @Test void cancellationCommittedJustBeforeTerminalLockPreventsAssistantAndSuccessEvent() {
        prepare(run);
        when(workflows.execute(eq("wv1"),eq("input"),any())).thenReturn(result("SUCCEEDED"));
        when(runs.findByIdForUpdate("run")).thenAnswer(invocation -> { run.requestCancel(); return Optional.of(run); });
        service.convergeInterruptedRuns();
        assertThat(run.getState()).isEqualTo(RunState.CANCELLED);
        verify(messages,never()).save(any());
        verify(events).publish(eq("run"),eq("run.cancelled"),anyMap());
        verify(events,never()).publish(eq("run"),eq("run.completed"),anyMap());
    }
    private WorkflowRunResponse result(String state) {
        return new WorkflowRunResponse("wr1","wv1","checksum",state,"input","answer",Map.of(),"stopped",1L,List.of(),Instant.now(),Instant.now());
    }
}
