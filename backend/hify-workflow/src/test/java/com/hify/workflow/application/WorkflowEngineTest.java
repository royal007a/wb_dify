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
    @Test void conditionTreatsUserSuppliedOperatorsAsData() throws Exception {
        stored(draft(List.of(node("start","START"),
                node("route","CONDITION","expression","{{start.userMessage}} contains refund"),
                node("end","END","output","{{route.result}}")),edge("start","route"),branch("end",null,true)));
        assertThat(engine.execute("v1", "refund contains refund").output()).isEqualTo("true");
    }
    private void stored(WorkflowDraftRequest graph) throws Exception {
        when(app.requireVersion("v1")).thenReturn(new WorkflowVersion("v1","w1",1,1,JSON.writeValueAsString(graph),"digest", Instant.now()));
    }
}
