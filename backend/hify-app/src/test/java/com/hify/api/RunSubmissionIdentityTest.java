package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.RunEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.hify.application.RunEventBroker;
import com.hify.domain.AgentRun;
import com.hify.domain.RunState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:submission-identity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class RunSubmissionIdentityTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager caches;
    @org.springframework.boot.test.mock.mockito.SpyBean AgentRunRepository runs;
    @org.springframework.boot.test.mock.mockito.SpyBean com.hify.provider.api.ProviderQueryService providers;
    @Autowired com.hify.application.RunApplicationService service;
    @Autowired ChatMessageRepository messages;
    @Autowired RunEventRepository events;
    @Autowired RunEventBroker broker;
    // Capture dispatch without executing: the HTTP transaction really commits in the database.
    @MockBean(name="runExecutor") Executor executor;

    @AfterEach void restoreProvider() {
        jdbc.update("update providers set enabled=true where public_id='mock'");
        caches.getCache("provider-cache").clear();
    }

    @Test void committedSubmissionReplaysAfterProviderDisabledButDifferentBodyAndNewWorkDoNot() throws Exception {
        String conversation=conversation(), key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        long eventCount=events.count();
        assertThat(jdbc.update("update providers set enabled=false where public_id='mock'")).isEqualTo(1);
        caches.getCache("provider-cache").clear();
        assertThat(create(conversation,key,"hello",200).path("id").asText()).isEqualTo(id);
        assertThat(create(conversation,key,"changed",409).path("code").asInt()).isEqualTo(40901);
        create(conversation,UUID.randomUUID().toString(),"hello",409);
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).hasSize(1);
        assertThat(events.count()).isEqualTo(eventCount);
        verify(executor,times(1)).execute(any());
    }

    @Test void lookupIsReadOnlyScopedAndUncachedEvenWhenProviderDisabled() throws Exception {
        String conversation=conversation(), other=conversation(), key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        jdbc.update("update providers set enabled=false where public_id='mock'");
        caches.getCache("provider-cache").clear();
        long runCount=runs.count(),messageCount=messages.count(),eventCount=events.count();
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key",key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Vary","Idempotency-Key"));
        for(String candidate:java.util.List.of(other,UUID.randomUUID().toString())) {
            http.perform(get("/api/v1/conversations/{id}/runs/by-key",candidate).header("Idempotency-Key",key))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation)
                        .header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertThat(runs.count()).isEqualTo(runCount);
        assertThat(messages.count()).isEqualTo(messageCount);
        assertThat(events.count()).isEqualTo(eventCount);
        verify(executor,times(1)).execute(any());
    }

    @Test void missingLookupNeverCreatesAndRejectsInvalidKeys() throws Exception {
        String conversation=conversation();
        long before=runs.count();
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key","unknown"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Vary","Idempotency-Key"));
        for(String key:java.util.List.of(" ","a".repeat(129))) {
            http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key",key))
                    .andExpect(status().isBadRequest());
        }
        assertThat(runs.count()).isEqualTo(before);
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).isEmpty();
        verifyNoInteractions(executor);
    }

    @Test void sameKeyWithDifferentResumeIsConflictWithoutAdditionalWork() throws Exception {
        String conversation=conversation(), key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        long runCount=runs.count(),messageCount=messages.count(),eventCount=events.count();
        String changedBody=json.writeValueAsString(java.util.Map.of("message","hello","resume",
                java.util.Map.of("runId",UUID.randomUUID().toString(),"gapIds",java.util.List.of("gap-1"))));
        http.perform(post("/api/v1/conversations/{id}/runs",conversation).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content(changedBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40901));
        http.perform(post("/api/v1/conversations/{id}/runs",conversation).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(changedBody))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        assertThat(create(conversation,key,"hello",200).path("id").asText()).isEqualTo(id);
        assertThat(runs.count()).isEqualTo(runCount);
        assertThat(messages.count()).isEqualTo(messageCount);
        assertThat(events.count()).isEqualTo(eventCount);
        verify(executor,times(1)).execute(any());
    }

    @Test void foreignAndMissingResumeSourcesHaveIdenticalSafeErrorsWithoutWrites() throws Exception {
        String conversation=conversation(), other=conversation();
        String source=create(other,UUID.randomUUID().toString(),"source",202).path("id").asText();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        JsonNode previous=null;
        for (String candidate:java.util.List.of(source,UUID.randomUUID().toString())) {
            String key=UUID.randomUUID().toString();
            String body=json.writeValueAsString(java.util.Map.of("message","hello","resume",
                    java.util.Map.of("runId",candidate,"gapIds",java.util.List.of("gap-1"))));
            JsonNode rejected=json.readTree(http.perform(post("/api/v1/conversations/{id}/runs",conversation)
                            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.message").value(com.hify.common.ErrorCode.PARAM_ERROR.message()))
                    .andReturn().getResponse().getContentAsString());
            assertThat(rejected.toString()).doesNotContain(candidate,conversation,other);
            if(previous!=null) assertThat(rejected).isEqualTo(previous);
            previous=rejected;
            http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key",key))
                    .andExpect(status().isNotFound());
        }
        // The same source really exists: within its own conversation, the original state conflict remains.
        http.perform(post("/api/v1/conversations/{id}/runs",other).header("Idempotency-Key",UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of(
                                "message","hello","resume",java.util.Map.of("runId",source,"gapIds",java.util.List.of("gap-1"))))))
                .andExpect(status().isConflict());
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verify(executor,times(1)).execute(any());
        // A valid submission changes the same counters used by the rejected requests.
        create(conversation,UUID.randomUUID().toString(),"new work",202);
        assertThat(runs.count()).isEqualTo(beforeRuns+1);
        assertThat(messages.count()).isEqualTo(beforeMessages+1);
        verify(executor,times(2)).execute(any());
    }

    @Test void messageLimitRejectsBeforeWritesAndAcceptsTheExactBoundary() throws Exception {
        String conversation=conversation();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        JsonNode rejected=create(conversation,UUID.randomUUID().toString(),"x".repeat(20001),400);
        assertThat(rejected.path("code").asInt()).isEqualTo(40000);
        assertThat(rejected.toString()).doesNotContain("insert", "VARCHAR", "x".repeat(20));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.create(
                conversation, UUID.randomUUID().toString(), "x".repeat(20001)))
                .isInstanceOfSatisfying(com.hify.common.BizException.class,
                        error->assertThat(error.errorCode()).isEqualTo(com.hify.common.ErrorCode.PARAM_ERROR));
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verifyNoInteractions(executor);
        JsonNode accepted=create(conversation,UUID.randomUUID().toString(),"x".repeat(20000),202);
        assertThat(accepted.path("inputMessage").asText()).hasSize(20000);
        assertThat(runs.count()).isEqualTo(beforeRuns+1);
        assertThat(messages.count()).isEqualTo(beforeMessages+1);
        verify(executor,times(1)).execute(any());
    }

    @Test void deletingConversationAfterAdmissionReadIsNotAnIdempotencyConflict() throws Exception {
        String conversation=conversation();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{
            Object result=call.callRealMethod(); entered.countDown();
            assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return result;
        }).when(providers).requireEnabled("mock");
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var response=pool.submit(()->create(conversation,UUID.randomUUID().toString(),"hello",404));
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Separate connection commits deletion after create already read the row.
            assertThat(jdbc.update("delete from conversations where id=?",conversation)).isEqualTo(1);
            release.countDown();
            assertThat(response.get(10,java.util.concurrent.TimeUnit.SECONDS).path("code").asInt()).isEqualTo(40400);
            assertThat(runs.count()).isEqualTo(beforeRuns);
            assertThat(messages.count()).isEqualTo(beforeMessages);
            assertThat(events.count()).isEqualTo(beforeEvents);
            verifyNoInteractions(executor);
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void anotherUniqueConstraintIsSafeFailureNotReplay() throws Exception {
        String conversation=conversation();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        var cause=new org.hibernate.exception.ConstraintViolationException("synthetic SQL secret",
                new java.sql.SQLException("synthetic SQL secret","23505"),"uq_unrelated");
        doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic SQL secret",cause))
                .when(runs).saveAndFlush(any());
        JsonNode rejected=create(conversation,UUID.randomUUID().toString(),"hello",500);
        assertThat(rejected.path("code").asInt()).isEqualTo(50000);
        assertThat(rejected.toString()).doesNotContain("SQL", "secret", "uq_unrelated", "converge");
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verifyNoInteractions(executor);
    }

    @Test void localizedPostgresConflictReplaysUsingStructuredConstraintNotHibernateMessageExtraction() throws Exception {
        String conversation=conversation(),key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        var winner=runs.findById(id).orElseThrow();
        doReturn(java.util.Optional.empty(),java.util.Optional.of(winner))
                .when(runs).findByConversationIdAndIdempotencyKey(conversation,key);
        var detail=new org.postgresql.util.ServerErrorMessage(
                "SERROR\0C23505\0M重复键违反唯一约束\0nUQ_RUN_IDEMPOTENCY\0");
        var pg=new org.postgresql.util.PSQLException(detail);
        // Hibernate's English-text extractor cannot extract this localized name.
        var constraint=new org.hibernate.exception.ConstraintViolationException("localized",pg,(String)null);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("localized",constraint))
                .when(runs).saveAndFlush(any());
        assertThat(create(conversation,key,"hello",200).path("id").asText()).isEqualTo(id);
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verify(executor,times(1)).execute(any());
    }

    @Test void nulInputIsRejectedBeforeWritesOnBothHttpAndService() throws Exception {
        String conversation=conversation(),nul=String.valueOf((char)0);
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        assertThat(create(conversation,UUID.randomUUID().toString(),"bad"+nul,400).path("code").asInt()).isEqualTo(40000);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.create(conversation,"key","bad"+nul))
                .isInstanceOfSatisfying(com.hify.common.BizException.class,
                        error->assertThat(error.errorCode()).isEqualTo(com.hify.common.ErrorCode.PARAM_ERROR));
        for(var resume:java.util.List.of(java.util.Map.of("runId","bad"+nul,"gapIds",java.util.List.of("gap")),
                java.util.Map.of("runId","source","gapIds",java.util.List.of("gap"+nul)))) {
            http.perform(post("/api/v1/conversations/{id}/runs",conversation).header("Idempotency-Key",UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("message","hello","resume",resume))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        }
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verifyNoInteractions(executor);
        assertThat(create(conversation,UUID.randomUUID().toString(),"hello",202).path("inputMessage").asText()).isEqualTo("hello");
        verify(executor,times(1)).execute(any());
    }

    @Test void structuredPostgresFieldsOverrideMisleadingHibernateConstraintNames() throws Exception {
        String conversation=conversation();
        long beforeRuns=runs.count(),beforeMessages=messages.count(),beforeEvents=events.count();
        for(String fields:java.util.List.of("C23505\0nuq_other\0", "C23503\0nuq_run_idempotency\0", "C23505\0")) {
            var pg=new org.postgresql.util.PSQLException(new org.postgresql.util.ServerErrorMessage("SERROR\0M合成错误\0"+fields));
            var wrapper=new org.hibernate.exception.ConstraintViolationException("synthetic",pg,"uq_run_idempotency");
            doThrow(new org.springframework.dao.DataIntegrityViolationException("synthetic",wrapper)).when(runs).saveAndFlush(any());
            assertThat(create(conversation,UUID.randomUUID().toString(),"hello",500).path("code").asInt()).isEqualTo(50000);
        }
        assertThat(runs.count()).isEqualTo(beforeRuns);
        assertThat(messages.count()).isEqualTo(beforeMessages);
        assertThat(events.count()).isEqualTo(beforeEvents);
        verifyNoInteractions(executor);
    }

    @Test void h2SameKeyRaceStillConvergesWithOneUserMessageAndDispatch() throws Exception {
        String conversation=conversation(), key=UUID.randomUUID().toString();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call->{Object result=call.callRealMethod();barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);return result;})
                .when(providers).requireEnabled("mock");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.create(conversation,key,"hello"));
            var b=pool.submit(()->service.create(conversation,key,"hello"));
            var first=a.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var second=b.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.run().getId()).isEqualTo(second.run().getId());
            assertThat(first.replayed()).isNotEqualTo(second.replayed());
            assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).hasSize(1);
            verify(executor,times(1)).execute(any());
        } finally {pool.shutdownNow();}
    }

    @Test void repeatedAndTerminalCancellationAreIdempotentAndPreCancelDoesNoModelWork() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> queued=new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation->{queued.set(invocation.getArgument(0));return null;}).when(executor).execute(any());
        String conversation=conversation(), id=create(conversation,UUID.randomUUID().toString(),"hello",202).path("id").asText();
        assertThat(queued.get()).isNotNull();
        JsonNode first=json.readTree(http.perform(post("/api/v1/runs/{id}/cancellations",id)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING")).andReturn().getResponse().getContentAsString());
        assertThat(first.path("cancelRequestedAt").asText()).isNotBlank();
        http.perform(post("/api/v1/runs/{id}/cancellations",id)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.cancelRequestedAt").value(first.path("cancelRequestedAt").asText()));
        assertThat(events.findByRunIdOrderByIdAsc(id)).filteredOn(e->e.getEventType().equals("run.cancel.requested")).hasSize(1);

        queued.get().run(); // Exactly the production dispatch worker, after the HTTP cancellation committed.
        JsonNode terminal=json.readTree(http.perform(get("/api/v1/runs/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED")).andExpect(jsonPath("$.terminalReason").value("CANCELLED"))
                .andReturn().getResponse().getContentAsString());
        long before=events.count();
        JsonNode cancelledAgain=json.readTree(http.perform(post("/api/v1/runs/{id}/cancellations",id)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        assertThat(cancelledAgain).isEqualTo(terminal);
        assertThat(events.count()).isEqualTo(before);
        assertThat(events.findByRunIdOrderByIdAsc(id)).filteredOn(e->e.getEventType().equals("run.cancelled")).hasSize(1);
        assertThat(events.findByRunIdOrderByIdAsc(id)).noneMatch(e->e.getEventType().startsWith("model.")||e.getEventType().startsWith("tool."));
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).singleElement()
                .satisfies(message->assertThat(message.getRole()).isEqualTo("user"));
        verify(executor,times(1)).execute(any());
    }

    @ParameterizedTest @EnumSource(value=RunState.class,names={"COMPLETED","FAILED"})
    void cancellationCannotRewriteAnExistingSuccessOrFailure(RunState state) throws Exception {
        // Seed terminal rows explicitly: this tests cancellation's HTTP/DB behavior,
        // not how the model or workflow reached these states.
        String conversation=conversation(),id=UUID.randomUUID().toString();
        AgentRun fixture=new AgentRun(id,conversation,UUID.randomUUID().toString(),"fixture-hash","original",java.time.Instant.now());
        fixture.finish(state,state==RunState.COMPLETED?"COMPLETED":"MODEL_ERROR","original-result",2,1);
        runs.saveAndFlush(fixture);
        broker.publish(id,state==RunState.COMPLETED?"run.completed":"run.failed",java.util.Map.of("state",state.name()));
        JsonNode before=json.readTree(http.perform(get("/api/v1/runs/{id}",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(state.name())).andReturn().getResponse().getContentAsString());
        assertThat(before.path("cancelRequestedAt").isNull()).isTrue();
        assertThat(events.findByRunIdOrderByIdAsc(id)).hasSize(1);
        long eventCount=events.count(),messageCount=messages.count();
        for(int attempt=0;attempt<2;attempt++) {
            JsonNode after=json.readTree(http.perform(post("/api/v1/runs/{id}/cancellations",id)).andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString());
            assertThat(after).isEqualTo(before);
            assertThat(events.count()).isEqualTo(eventCount);
            assertThat(messages.count()).isEqualTo(messageCount);
        }
        assertThat(runs.findById(id).orElseThrow().getCancelRequestedAt()).isNull();
        verifyNoInteractions(executor);
    }

    private String conversation() throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"demo-agent\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }
    private JsonNode create(String conversation,String key,String message,int status) throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations/{id}/runs",conversation)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("message",message))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
}
