package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.RunApplicationService;
import com.hify.application.RunEventBroker;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:run-dispatch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "hify.run-timeout=10s"})
class RunDispatchIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired AgentRunRepository runs;
    @Autowired com.hify.infra.RunEventRepository events;
    @Autowired RunApplicationService service;
    @MockBean(name="runExecutor") Executor executor;
    @SpyBean RunEventBroker broker;
    ExecutorService callers;
    @BeforeEach void callers(){callers=Executors.newFixedThreadPool(2);}
    @AfterEach void cleanup() throws Exception {callers.shutdownNow();assertThat(callers.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}

    @Test void recoveryCannotRejectOrCleanUpAnAlreadyOwnedDispatch() throws Exception {
        String conversation=conversation(),key=UUID.randomUUID().toString();
        CountDownLatch submitted=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger submissions=new AtomicInteger();
        doAnswer(invocation->{if(submissions.incrementAndGet()>1)throw new RejectedExecutionException("duplicate capacity refusal");
            submitted.countDown();await(release);((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        try {
            var created=callers.submit(()->service.create(conversation,key,"hi"));await(submitted);
            String id=runs.findByConversationIdAndIdempotencyKey(conversation,key).orElseThrow().getId();
            service.convergeInterruptedRuns();
            assertThat(submissions).hasValue(1);
            assertThat(runs.findById(id).orElseThrow().getState()).isEqualTo(RunState.RUNNING);
            assertThat(localMap("cancellations").containsKey(id)).isTrue();
            release.countDown();created.get(3,TimeUnit.SECONDS);
            assertThat(runs.findById(id).orElseThrow().getState()).isEqualTo(RunState.COMPLETED);
            assertClean(id);
        } finally {release.countDown();}
    }

    @Test void recoveryBetweenCreateCommitAndDispatchDoesNotExecuteTerminalRunAgain() throws Exception {
        String conversation=conversation(),key=UUID.randomUUID().toString();
        CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger submissions=new AtomicInteger();
        doAnswer(invocation->{Object result=invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){committed.countDown();await(release);}
            });return result;})
                .when(broker).publish(anyString(),eq("run.started"),anyMap());
        doAnswer(invocation->{submissions.incrementAndGet();((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        try {
            var created=callers.submit(()->service.create(conversation,key,"hi"));await(committed);
            service.convergeInterruptedRuns();
            assertThat(submissions).hasValue(1);
            release.countDown();var result=created.get(3,TimeUnit.SECONDS);
            assertThat(submissions).hasValue(1);
            assertThat(result.run().getState()).isEqualTo(RunState.COMPLETED);
            assertClean(result.run().getId());
        } finally {release.countDown();}
    }

    @Test void cancelReturningAfterRejectedOwnerCleanupDoesNotRecreateALeakedFlag() throws Exception {
        String conversation=conversation(),key=UUID.randomUUID().toString();
        CountDownLatch submitted=new CountDownLatch(1),refuse=new CountDownLatch(1),cancelCommitted=new CountDownLatch(1),returnCancel=new CountDownLatch(1);
        doAnswer(invocation->{submitted.countDown();await(refuse);throw new RejectedExecutionException("capacity");}).when(executor).execute(any());
        doAnswer(invocation->{Object value=invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){cancelCommitted.countDown();await(returnCancel);}
            });return value;}).when(broker).publish(anyString(),eq("run.cancel.requested"),anyMap());
        try {
            var create=callers.submit(()->service.create(conversation,key,"hi"));await(submitted);
            String id=runs.findByConversationIdAndIdempotencyKey(conversation,key).orElseThrow().getId();
            var cancel=callers.submit(()->service.cancel(id));await(cancelCommitted);
            refuse.countDown();assertThat(create.get(3,TimeUnit.SECONDS).run().getState()).isEqualTo(RunState.CANCELLED);
            assertThat(localMap("cancellations").containsKey(id)).isFalse();
            returnCancel.countDown();cancel.get(3,TimeUnit.SECONDS);
            assertClean(id);
        } finally {refuse.countDown();returnCancel.countDown();}
    }

    @Test void cancellationBeforeAnyDispatchIsReadBeforeModelOrToolExecution() throws Exception {
        String conversation=conversation(),key=UUID.randomUUID().toString();
        CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation->{Object result=invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){committed.countDown();await(release);}
            });return result;}).when(broker).publish(anyString(),eq("run.started"),anyMap());
        doAnswer(invocation->{((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        try {
            var create=callers.submit(()->service.create(conversation,key,"hi"));await(committed);
            String id=runs.findByConversationIdAndIdempotencyKey(conversation,key).orElseThrow().getId();
            service.cancel(id); // no dispatch-owned flag exists yet
            assertThat(localMap("cancellations").containsKey(id)).isFalse();
            release.countDown();assertThat(create.get(3,TimeUnit.SECONDS).run().getState()).isEqualTo(RunState.CANCELLED);
            assertThat(events.findByRunIdOrderByIdAsc(id)).noneMatch(e->e.getEventType().equals("model.started") || e.getEventType().equals("tool.call.started"));
            assertClean(id);
        } finally {release.countDown();}
    }

    private static void await(CountDownLatch latch) {
        try{assertThat(latch.await(5,TimeUnit.SECONDS)).as("controlled interleaving reached").isTrue();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private java.util.Map<?,?> localMap(String name){return (java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,name);}
    private void assertClean(String id){
        assertThat(localMap("cancellations").containsKey(id)).isFalse();
        assertThat(localMap("activeAttempts").containsKey(id)).isFalse();
        assertThat(localMap("dispatchOwners").containsKey(id)).isFalse();
    }
    private String conversation() throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"demo-agent\",\"title\":\"dispatch\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }
}
