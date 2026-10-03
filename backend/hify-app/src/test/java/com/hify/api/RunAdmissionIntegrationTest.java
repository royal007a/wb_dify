package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.RunApplicationService;
import com.hify.domain.AgentRun;
import com.hify.domain.RunState;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.RunEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:run-admission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "hify.run-timeout=5s"})
class RunAdmissionIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired AgentRunRepository runs;
    @Autowired ChatMessageRepository messages;
    @Autowired RunEventRepository events;
    @Autowired RunApplicationService service;
    @MockBean(name="runExecutor") Executor executor;
    ThreadPoolExecutor saturated;
    CountDownLatch release;

    @BeforeEach void fillRealBoundedExecutor() throws Exception {
        release=new CountDownLatch(1);CountDownLatch entered=new CountDownLatch(1);
        saturated=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),new ThreadPoolExecutor.AbortPolicy());
        saturated.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
        doAnswer(invocation->{saturated.execute(invocation.getArgument(0));return null;}).when(executor).execute(any());
    }
    @AfterEach void cleanup(){release.countDown();saturated.shutdownNow();}

    @Test void capacityRejectionIsTerminalAndIdempotentlyReplayableWithoutLeakingExecutorDetails() throws Exception {
        String conversation=conversation();String key=UUID.randomUUID().toString();
        JsonNode first=create(conversation,key,202);
        assertThat(first.path("state").asText()).isEqualTo("FAILED");
        assertThat(first.path("terminalReason").asText()).isEqualTo("EXECUTOR_REJECTED");
        String id=first.path("id").asText();
        assertThat(runs.findById(id).orElseThrow().getOutputMessage()).isEqualTo("Run execution capacity exhausted; retry with a new idempotency key.");
        JsonNode replay=create(conversation,key,200);
        assertThat(replay.path("id").asText()).isEqualTo(id);
        assertThat(replay.path("state").asText()).isEqualTo("FAILED");
        verify(executor,times(1)).execute(any());
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).hasSize(1);
        String eventJson=http.perform(get("/api/v1/runs/{id}/events",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(eventJson).contains("run.failed","EXECUTOR_REJECTED").doesNotContain("ThreadPoolExecutor","pool size","task =");
        assertThat(events.findByRunIdOrderByIdAsc(id).stream().filter(e->e.getEventType().equals("run.failed"))).hasSize(1);
        assertNoLocalState(id);
        var stream=http.perform(get("/api/v1/runs/{id}/events/stream",id)).andExpect(request().asyncStarted()).andReturn();
        String replayed=http.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replayed).contains("event:run.failed", "EXECUTOR_REJECTED").doesNotContain("ThreadPoolExecutor");

        doAnswer(invocation->{((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        JsonNode retry=create(conversation,UUID.randomUUID().toString(),202);
        assertThat(retry.path("id").asText()).isNotEqualTo(id);
        assertThat(runs.findById(retry.path("id").asText()).orElseThrow().getState()).isEqualTo(RunState.COMPLETED);
    }

    @Test void recoveryRejectionDoesNotAbortTheRestOfThePendingRuns() throws Exception {
        String conversation=conversation();String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        runs.saveAndFlush(new AgentRun(first,conversation,"one","hash","hi",Instant.now()));
        runs.saveAndFlush(new AgentRun(second,conversation,"two","hash","hi",Instant.now()));
        AtomicInteger calls=new AtomicInteger();
        doAnswer(invocation->{if(calls.getAndIncrement()==0)saturated.execute(invocation.getArgument(0));
            else ((Runnable)invocation.getArgument(0)).run();return null;}).when(executor).execute(any());
        assertThatCode(service::convergeInterruptedRuns).doesNotThrowAnyException();
        assertThat(java.util.List.of(runs.findById(first).orElseThrow().getState(),runs.findById(second).orElseThrow().getState()))
                .containsExactlyInAnyOrder(RunState.FAILED,RunState.COMPLETED);
        assertThat(calls).hasValue(2);assertNoLocalState(first);assertNoLocalState(second);
    }

    @Test void persistedCancellationRacingAdmissionWinsTheRejection() throws Exception {
        String conversation=conversation();
        doAnswer(invocation->{var pending=runs.findByStateIn(java.util.List.of(RunState.RUNNING)).stream()
                    .filter(r->r.getConversationId().equals(conversation)).findFirst().orElseThrow();
            service.cancel(pending.getId());saturated.execute(invocation.getArgument(0));return null;}).when(executor).execute(any());
        JsonNode result=create(conversation,UUID.randomUUID().toString(),202);
        assertThat(result.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(result.path("terminalReason").asText()).isEqualTo("CANCELLED");
        String id=result.path("id").asText();
        assertThat(events.findByRunIdOrderByIdAsc(id).stream().filter(e->e.getEventType().equals("run.cancelled"))).hasSize(1);
        assertNoLocalState(id);
    }

    private String conversation() throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"demo-agent\",\"title\":\"admission\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
    }
    private JsonNode create(String conversation,String key,int status) throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations/{id}/runs",conversation)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    private void assertNoLocalState(String id) {
        for(String name:java.util.List.of("cancellations","activeAttempts","dispatchOwners")) {
            var state=(java.util.Map<?,?>)org.springframework.test.util.ReflectionTestUtils.getField(service,name);
            assertThat(state.containsKey(id)).isFalse();
        }
    }
}
