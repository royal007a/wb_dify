package com.hify.api;

import com.hify.common.ExecutionLifecycle;
import com.hify.common.ExecutionSuspendedException;
import com.hify.domain.RunState;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:knowledge-admission-shutdown;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class KnowledgeAdmissionShutdownTest extends KnowledgeFinishIntegrationTest {
    @SpyBean ExecutionLifecycle lifecycle;
    @AfterEach void resetSignal(){doReturn(false).when(lifecycle).isStopping();}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void retrievalShutdownSignalIsNotPermanentSourceFailure(boolean wrappedIoFailure) throws Exception {
        doAnswer(call->{
            if(wrappedIoFailure){doReturn(true).when(lifecycle).isStopping();throw new IllegalStateException("database read interrupted");}
            throw new ExecutionSuspendedException();
        }).when(source).searchRevision(anyString(),anyString(),anyInt());
        String id=startWith(List.of(base()),"退货期限");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end && events.findByRunIdOrderByIdAsc(id).stream()
                .noneMatch(e->e.getEventType().equals("run.interrupted")||e.getEventType().equals("run.failed")))Thread.sleep(10);
        var run=runs.get(id);
        assertThat(run.getState()).as("%s: %s",run.getTerminalReason(),run.getOutputMessage()).isEqualTo(RunState.RUNNING);
        assertThat(events.findByRunIdOrderByIdAsc(id)).anyMatch(e->e.getEventType().equals("run.interrupted"))
                .noneMatch(e->e.getEventType().equals("run.failed"));
        assertThat(calls).hasValue(0);
        assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,run.getConversationId())).isZero();
    }
}
