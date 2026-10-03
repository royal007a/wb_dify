package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.*;
import com.hify.infra.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class RunEventBrokerTest {
    private final RunEventRepository events=mock(RunEventRepository.class);
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final AgentRun run=new AgentRun("run","conversation","key","hash","input",Instant.now());
    private final List<RunEvent> committed=new CopyOnWriteArrayList<>(), staged=new CopyOnWriteArrayList<>();
    private final RunEventBroker broker=new RunEventBroker(events,runs,new ObjectMapper());
    private MockMvc http;
    private long nextId=1;

    @BeforeEach void setup() {
        when(runs.findById("run")).thenReturn(Optional.of(run));
        when(runs.findByIdForUpdate("run")).thenReturn(Optional.of(run));
        when(events.findTopByRunIdOrderBySequenceNoDesc("run")).thenAnswer(i->java.util.stream.Stream.concat(committed.stream(),staged.stream()).max(Comparator.comparingLong(RunEvent::getSequenceNo)));
        when(events.saveAndFlush(any())).thenAnswer(i->{RunEvent e=i.getArgument(0);ReflectionTestUtils.setField(e,"id",nextId++);staged.add(e);return e;});
        when(events.findByRunIdOrderByIdAsc("run")).thenAnswer(i->List.copyOf(committed));
        when(events.findByRunIdAndIdGreaterThanOrderByIdAsc(eq("run"),anyLong())).thenAnswer(i->committed.stream().filter(e->e.getId()>(Long)i.getArgument(1)).toList());
        when(events.findByRunIdAndIdGreaterThanOrderByIdAsc(eq("run"),anyLong(),any())).thenAnswer(i->committed.stream().filter(e->e.getId()>(Long)i.getArgument(1)).limit(32).toList());
        when(events.findById(anyLong())).thenAnswer(i->committed.stream().filter(e->e.getId().equals(i.getArgument(0))).findFirst());
        when(events.findFirstByRunIdAndEventTypeInOrderByIdAsc(eq("run"),anyList())).thenAnswer(i->committed.stream()
                .filter(e->((List<String>)i.getArgument(1)).contains(e.getEventType())).findFirst());
        http=MockMvcBuilders.standaloneSetup(new StreamController(broker)).build();
    }
    @AfterEach void cleanup() { if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false); }
    @AfterEach void stopBroker() {broker.close();}

    @Test void rollbackMustNeverLeakAVisibleEvent() throws Exception {
        MvcResult stream=http.perform(get("/stream")).andReturn();
        begin();broker.publish("run","message.delta",Map.of("delta","rollback-secret"));
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("rollback-secret");
        callbacks().forEach(s->s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));staged.clear();
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("rollback-secret");
    }
    @Test void committedEventIsDeliveredOnceEvenWhenReplayPrecedesCommitCallback() throws Exception {
        begin();broker.publish("run","message.delta",Map.of("delta","hello"));
        committed.addAll(staged);staged.clear();
        MvcResult stream=http.perform(get("/stream")).andReturn();
        callbacks().forEach(TransactionSynchronization::afterCommit);
        await().untilAsserted(()->assertThat(stream.getResponse().getContentAsString().split("event:message.delta",-1)).hasSize(2));
    }
    @Test void terminalRowWithoutTerminalEventKeepsSubscriptionOpenUntilProjection() throws Exception {
        run.finish(RunState.CANCELLED,"CANCELLED","cancelled",0,0);
        MvcResult stream=http.perform(get("/stream")).andReturn();
        assertThatThrownBy(()->stream.getAsyncResult(20)).isInstanceOf(IllegalStateException.class);
        begin();broker.publish("run","run.cancelled",Map.of("state","CANCELLED"));commit();
        assertThatCode(()->stream.getAsyncResult(2000)).doesNotThrowAnyException();
        assertThat(stream.getResponse().getContentAsString()).contains("event:run.cancelled");
    }
    @Test void outOfOrderCommitCallbacksDrainCommittedEventsInOrderWithoutDuplicates() throws Exception {
        MvcResult stream=http.perform(get("/stream")).andReturn();
        begin();broker.publish("run","message.delta",Map.of("delta","first"));
        List<TransactionSynchronization> first=callbacks();committed.addAll(staged);staged.clear();cleanup();
        begin();broker.publish("run","message.delta",Map.of("delta","second"));
        List<TransactionSynchronization> second=callbacks();committed.addAll(staged);staged.clear();
        second.forEach(TransactionSynchronization::afterCommit);first.forEach(TransactionSynchronization::afterCommit);
        await().untilAsserted(()->assertThat(stream.getResponse().getContentAsString()).contains("second"));
        String body=stream.getResponse().getContentAsString();
        assertThat(body).contains("first","second");assertThat(body.indexOf("first")).isLessThan(body.indexOf("second"));
        assertThat(body.split("event:message.delta",-1)).hasSize(3);
    }
    @Test void reconnectAfterConsumedTerminalClosesWithoutReplayingIt() throws Exception {
        begin();RunEvent end=broker.publish("run","run.completed",Map.of("state","COMPLETED"));commit();cleanup();
        MvcResult stream=http.perform(get("/stream").param("after",end.getId().toString())).andReturn();
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("event:run.completed");
        assertThatCode(()->stream.getAsyncResult(2000)).doesNotThrowAnyException();
    }
    @Test void bareUnproxiedPublicationWithoutTransactionIsRejectedBeforeWriting() {
        assertThatThrownBy(()->broker.publish("run","message.delta",Map.of("delta","no transaction")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        verify(events,never()).saveAndFlush(any());
    }
    private void begin(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);}
    private List<TransactionSynchronization> callbacks(){return TransactionSynchronizationManager.getSynchronizations();}
    private void commit(){committed.addAll(staged);staged.clear();callbacks().forEach(TransactionSynchronization::afterCommit);}
    @RestController static class StreamController {
        private final RunEventBroker broker;StreamController(RunEventBroker broker){this.broker=broker;}
        @GetMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter stream(@RequestParam(required=false) Long after,jakarta.servlet.http.HttpServletResponse response){return broker.subscribe("run",after,response::isCommitted);}
    }
}
