package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.domain.WorkflowVersion;
import com.hify.workflow.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hify.workflow.application.WorkflowFixtures.*;

class WorkflowEngineTest {
    private final WorkflowApplicationService app = mock(WorkflowApplicationService.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final KnowledgeRetrievalPort knowledge = mock(KnowledgeRetrievalPort.class);
    private final WorkflowEngine engine = new WorkflowEngine(app, runs, nodes, knowledge, JSON, new WorkflowGraphValidator(), Runnable::run, java.time.Duration.ofSeconds(60));

    @BeforeEach void saveReturnsPersistedRun() { when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0)); }

    @Test void aggregateExecutesOnlySelectedBranchAndKeepsPinnedVersion() throws Exception {
        var version = stored(WorkflowAggregationTest.graph(WorkflowAggregationTest.merge("left.result", "right.result")));
        for (String side : List.of("left", "right")) {
            clearInvocations(nodes);
            var result = engine.executePinned("v1", version.getChecksum(), side, com.hify.common.ExecutionControl.none());
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(result.output()).isEqualTo(side.equals("left") ? "L" : "R");
            assertThat(result.context()).containsEntry("merge.result", result.output());
            assertThat(result.context()).containsKey(side + ".result")
                    .doesNotContainKey((side.equals("left") ? "right" : "left") + ".result");
            verify(nodes, times(10)).save(any()); // five nodes, started and succeeded each
        }
    }

    @Test void cancelledAggregateRunRecordsCancellationWithoutExecutingAnyNode() throws Exception {
        var version = stored(WorkflowAggregationTest.graph(WorkflowAggregationTest.merge("left.result", "right.result")));
        var control = com.hify.common.ExecutionControl.withTimeout(java.time.Duration.ofSeconds(60), () -> true);
        var result = engine.executePinned("v1", version.getChecksum(), "left", control);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.context()).doesNotContainKeys("left.result", "right.result", "merge.result");
        verify(runs, times(2)).save(any()); // existing engine creates and settles a cancelled run
        verify(nodes, never()).save(any());
        verifyNoInteractions(knowledge);
    }

    @Test void pinnedExecutionValidatesAndUsesTheSameLoadedVersion() throws Exception {
        var first=stored(draft(List.of(node("start","START"),node("end","END","output","original")),edge("start","end")));
        String changed=WorkflowPublishedGraph.write(draft(List.of(node("start","START"),node("end","END","output","different")),edge("start","end")),JSON);
        var second=new WorkflowVersion("v1","w1",1,1,changed,WorkflowPublishedGraph.checksum(changed),Instant.now());
        when(app.requireVersion("v1")).thenReturn(first,second);
        assertThat(engine.executePinned("v1",first.getChecksum(),"input",com.hify.common.ExecutionControl.none()).output()).isEqualTo("original");
        verify(app,times(1)).requireVersion("v1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"wrong"})
    void missingOrChangedPinnedChecksumCannotStartExecution(String expected) throws Exception {
        stored(chain(2));
        assertThatThrownBy(()->engine.executePinned("v1",expected,"input",com.hify.common.ExecutionControl.none()))
                .isInstanceOf(com.hify.workflow.api.WorkflowDefinitionException.class)
                .satisfies(e->assertThat(((BizException)e).errorCode()).isEqualTo(com.hify.common.ErrorCode.CONFLICT));
        verifyNoInteractions(runs,nodes,knowledge);
    }

    @Test void matchingPinnedChecksumStillRequiresRawDslIntegrity() throws Exception {
        var first=stored(chain(2));
        when(app.requireVersion("v1")).thenReturn(new WorkflowVersion("v1","w1",1,1,"{}",first.getChecksum(),Instant.now()));
        assertThatThrownBy(()->engine.executePinned("v1",first.getChecksum(),"input",com.hify.common.ExecutionControl.none())).isInstanceOf(BizException.class);
        verifyNoInteractions(runs,nodes,knowledge);
    }

    @Test void fiftyStepGraphReallyCompletes() throws Exception {stored(chain(50));assertThat(engine.execute("v1","input").status()).isEqualTo("SUCCEEDED");}
    @Test void quotedOperatorsAreLiteralData() throws Exception {
        stored(condition("{{start.userMessage}} == 'a contains b'"));
        assertThat(engine.execute("v1","a contains b").output()).isEqualTo("true");
        assertThat(engine.execute("v1","a").output()).isEqualTo("false");
    }
    @Test void invalidOldConditionIsRejectedBeforeCreatingRun() throws Exception {
        stored(condition("hello"));assertThatThrownBy(()->engine.execute("v1","input")).isInstanceOf(BizException.class);
        verifyNoInteractions(runs,nodes,knowledge);
    }

    @Test void rejectsOldInvalidPublishedGraphBeforeAnyExecutionOrWrite() throws Exception {
        stored(diamond("{{left.result}}"));
        assertThatThrownBy(() -> engine.execute("v1", "input")).isInstanceOf(BizException.class);
        verifyNoInteractions(runs, nodes, knowledge);
    }
    @Test void startVariableUsesActualStartKey() throws Exception {
        stored(draft(List.of(node("entry","START"),node("end","END","output","{{entry.userMessage}}")),edge("entry","end")));
        var result = engine.execute("v1", "actual input");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.output()).isEqualTo("actual input");
        assertThat(result.context()).containsEntry("entry.userMessage", "actual input").doesNotContainKey("start.userMessage");
    }
    @Test void shutdownAfterEndComputedDoesNotRewriteSucceededAsInterrupted() throws Exception {
        stored(draft(List.of(node("start","START"),node("end","END","output","done")),edge("start","end")));
        var saved=new java.util.concurrent.atomic.AtomicReference<com.hify.workflow.domain.WorkflowRun>();
        when(runs.save(any())).thenAnswer(invocation->{saved.set(invocation.getArgument(0));return saved.get();});
        var lifecycle=mock(com.hify.common.ExecutionLifecycle.class);
        when(lifecycle.isStopping()).thenAnswer(invocation->saved.get()!=null && "SUCCEEDED".equals(saved.get().getStatus()));
        var engine=new WorkflowEngine(app,runs,nodes,knowledge,JSON,new WorkflowGraphValidator(),Runnable::run,java.time.Duration.ofSeconds(60),lifecycle);
        assertThat(engine.execute("v1","input").status()).isEqualTo("SUCCEEDED");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void signalAfterFinalCalculationCannotRewriteExecutionHistory(boolean deadline) throws Exception {
        stored(draft(List.of(node("start","START"),node("end","END","output","done")),edge("start","end")));
        var saved=new java.util.concurrent.atomic.AtomicReference<com.hify.workflow.domain.WorkflowRun>();
        when(runs.save(any())).thenAnswer(invocation->{saved.set(invocation.getArgument(0));return saved.get();});
        var control=mock(com.hify.common.ExecutionControl.class);
        when(control.withShutdown(any())).thenReturn(control);
        if(deadline)when(control.isExpired()).thenAnswer(invocation->saved.get()!=null&&"SUCCEEDED".equals(saved.get().getStatus()));
        else when(control.isCancelled()).thenAnswer(invocation->saved.get()!=null&&"SUCCEEDED".equals(saved.get().getStatus()));
        assertThat(engine.execute("v1","input",control).status()).isEqualTo("SUCCEEDED");
    }
    @Test void conditionTreatsUserSuppliedOperatorsAsData() throws Exception {
        stored(draft(List.of(node("start","START"),
                node("route","CONDITION","expression","{{start.userMessage}} contains refund"),
                node("end","END","output","{{route.result}}")),edge("start","route"),branch("end",null,true)));
        assertThat(engine.execute("v1", "refund contains refund").output()).isEqualTo("true");
    }
    private WorkflowVersion stored(WorkflowDraftRequest graph) throws Exception {
        String published=WorkflowPublishedGraph.write(graph,JSON);
        var version=new WorkflowVersion("v1","w1",1,1,published,WorkflowPublishedGraph.checksum(published), Instant.now());
        when(app.requireVersion("v1")).thenReturn(version);
        return version;
    }
}
