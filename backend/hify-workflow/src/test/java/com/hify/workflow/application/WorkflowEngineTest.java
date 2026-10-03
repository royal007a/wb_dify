package com.hify.workflow.application;

import com.hify.common.BizException;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.domain.WorkflowVersion;
import com.hify.workflow.infrastructure.*;
import org.junit.jupiter.api.Test;
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
    private final WorkflowEngine engine = new WorkflowEngine(app, runs, nodes, knowledge, JSON, new WorkflowGraphValidator());

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
