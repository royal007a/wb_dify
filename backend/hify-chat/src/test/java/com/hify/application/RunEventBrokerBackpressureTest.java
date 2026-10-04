package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.*;
import com.hify.infra.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RunEventBrokerBackpressureTest {
    final RunEventRepository events = mock(RunEventRepository.class);
    final AgentRunRepository runs = mock(AgentRunRepository.class);
    final RunEventBroker broker = new RunEventBroker(events, runs, new ObjectMapper());
    final ExecutorService caller = Executors.newCachedThreadPool();

    @BeforeEach void setup() {
        when(runs.findById(anyString())).thenAnswer(i -> Optional.of(run(i.getArgument(0))));
        when(runs.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.of(run(i.getArgument(0))));
        when(events.saveAndFlush(any())).thenAnswer(i -> { RunEvent e = i.getArgument(0); ReflectionTestUtils.setField(e,"id",1L); return e; });
    }
    @AfterEach void stop() throws Exception {
        caller.shutdownNow();
        for (var method : broker.getClass().getMethods()) if (method.getName().equals("close") && method.getParameterCount() == 0) method.invoke(broker);
    }
    AgentRun run(String id) { return new AgentRun(id,"conversation","key","hash","input",Instant.now()); }

    @Test void blockedSendCannotHoldCommitCallbackOrCollidingRunOrHeartbeat() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        SseEmitter blocking = new SseEmitter() {
            @Override public void send(SseEventBuilder event) throws java.io.IOException {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test release timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException("test stopped"); }
            }
        };
        RunEvent event = new RunEvent("run-Aa",1,"message.delta","{\"delta\":\"hello\"}");
        ReflectionTestUtils.setField(event,"id",1L);
        // Finish stubbing before the sender can call this shared mock. Changing a
        // Mockito stub concurrently with drain can attach it to the wrong call.
        when(events.findByRunIdAndIdGreaterThanOrderByIdAsc(eq("run-Aa"),anyLong(),any())).thenAnswer(i -> (Long)i.getArgument(1) < 1 ? List.of(event) : List.of());
        var responseReady = new java.util.concurrent.atomic.AtomicBoolean(false);
        broker.subscribe("run-Aa", null, responseReady::get);
        // Replace only this subscriber's transport with a deliberately blocked socket equivalent.
        Map<String, ? extends Collection<?>> subscribers = (Map<String, ? extends Collection<?>>) ReflectionTestUtils.getField(broker,"subscribers");
        Object sub = subscribers.get("run-Aa").iterator().next();
        ReflectionTestUtils.setField(sub,"emitter",blocking);
        verify(events,never()).findFirstByRunIdAndEventTypeInOrderByIdAsc(anyString(),any());
        verify(events,never()).findByRunIdAndIdGreaterThanOrderByIdAsc(anyString(),anyLong(),any());
        responseReady.set(true);
        Future<?> commit = caller.submit(() -> publishAndCommit("run-Aa"));
        try {
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            commit.get(200,TimeUnit.MILLISECONDS);
            assertThat("run-Aa".hashCode()).isEqualTo("run-BB".hashCode());
            // These commits start AFTER send entered, while the sender is still blocked.
            assertThat(release.getCount()).isEqualTo(1);
            caller.submit(() -> publishAndCommit("run-Aa")).get(200,TimeUnit.MILLISECONDS);
            caller.submit(() -> publishAndCommit("run-BB")).get(200,TimeUnit.MILLISECONDS);
            verify(events,times(2)).saveAndFlush(argThat(e -> e.getRunId().equals("run-Aa")));
            verify(events).saveAndFlush(argThat(e -> e.getRunId().equals("run-BB")));
            caller.submit(() -> broker.subscribe("run-BB",null)).get(200,TimeUnit.MILLISECONDS);
            caller.submit(broker::heartbeat).get(200,TimeUnit.MILLISECONDS);
        } finally { release.countDown(); }
    }

    void publishAndCommit(String runId) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            broker.publish(runId,"message.delta",Map.of("delta","hello"));
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void foreignAndUnknownCursorsAreRejectedBeforeSubscribing() {
        RunEvent foreign = new RunEvent("another-run",1,"run.completed","{}");
        ReflectionTestUtils.setField(foreign,"id",999L);
        when(events.findById(999L)).thenReturn(Optional.of(foreign));
        for (long cursor : new long[]{999,1000,-1})
            assertThatThrownBy(() -> broker.subscribe("run-Aa",cursor)).isInstanceOf(RuntimeException.class);
    }

    @Test void capacityIsBoundedAndHistoryIsNotReadBeforeMvcCommitsHandshake() {
        var limited = new RunEventBroker(events,runs,new ObjectMapper(),1);
        try {
            limited.subscribe("run-Aa",null,()->false);
            assertThatThrownBy(()->limited.subscribe("run-BB",null,()->false))
                    .isInstanceOfSatisfying(com.hify.common.BizException.class,e->assertThat(e.errorCode()).isEqualTo(com.hify.common.ErrorCode.SERVICE_UNAVAILABLE));
            verify(events,never()).findByRunIdAndIdGreaterThanOrderByIdAsc(anyString(),anyLong(),any());
            var workers=(ThreadPoolExecutor)ReflectionTestUtils.getField(limited,"senders");
            assertThat(workers.getPoolSize()).isEqualTo(1);
            assertThat(workers.getQueue()).isEmpty();
        } finally {limited.close();}
    }
}
