package com.hify.api;

import com.hify.domain.*;
import com.hify.infra.*;
import com.hify.memory.MemoryDigests;
import com.hify.runtime.RuntimeMessage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:knowledge-memory;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class KnowledgeMemoryBoundaryTest extends KnowledgeFinishIntegrationTest {
    @Autowired RunHistoryCommitRepository history;
    @Autowired HistoryDetailRefRepository detailRefs;
    @Autowired ContextSummaryRepository summaries;
    @Autowired AgentRunRepository runRows;

    @Test void withheldFinalTextIsNotPublishedThroughAnyMemorySurface() throws Exception {
        String kb=base();upload(kb,"退货期限是七天。");
        answer="唯一未核验正文：终身退货";
        String id=startWith(List.of(kb),"退货期限");awaitState(id,RunState.NEEDS_INPUT);
        String ref=legacyProjection(id);
        assertHidden(id,ref);
        db.update("update agent_runs set agent_version_id=null where id=?",id);
        assertHidden(id,ref); // an old unpinned Run is still protected by its checkpoint gate
        // Source Run, not caller Run, determines visibility, including the same conversation.
        AgentRun original=runs.get(id);String caller=UUID.randomUUID().toString();
        runRows.saveAndFlush(new AgentRun(caller,original.getConversationId(),UUID.randomUUID().toString(),"hash","query",Instant.now()));
        assertSearchAndDetailHidden(caller,ref);
        assertThat(history.findByRunIdAndOperationId(id,"model:1")).isPresent(); // retain recoverability
    }

    @Test void historyCommittedBeforeVerificationIsNotReadableWhileRunIsRunning() throws Exception {
        String kb=base();upload(kb,"退货期限是七天。");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");return call.callRealMethod();})
                .when(source).requireCanonicalChunk(anyString(),anyString());
        String id=startWith(List.of(kb),"退货期限");
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(runs.get(id).getState()).isEqualTo(RunState.RUNNING);
            assertThat(history.findByRunIdAndOperationId(id,"model:1")).isPresent();
            assertHidden(id,legacyProjection(id));
        } finally {release.countDown();}
        awaitState(id,RunState.COMPLETED);
        assertThat(runs.get(id).getOutputMessage()).isEqualTo(answer);
        assertHidden(id,legacyProjection(id)); // completion doesn't release all intermediate text
    }

    @Test void knowledgeRunDoesNotCreateNewRawMemoryIndex() throws Exception {
        String kb=base();upload(kb,"退货期限是七天。");answer="不带引用的内部回答";
        String id=startWith(List.of(kb),"退货期限");awaitState(id,RunState.NEEDS_INPUT);
        assertThat(detailRefs.findByRunIdOrderBySourceMessageIndexAsc(id)).isEmpty();
        assertThat(history.findByRunIdAndOperationId(id,"model:1")).isPresent();
    }

    private String legacyProjection(String id) throws Exception {
        var commit=history.findByRunIdAndOperationId(id,"model:1").orElseThrow();
        var messages=json.readTree(commit.getMessagesJson());int index=messages.size()-1;
        RuntimeMessage message=json.treeToValue(messages.get(index),RuntimeMessage.class);
        String digest=MemoryDigests.sha256(json.writeValueAsString(message));
        var existing=detailRefs.findByRunIdAndSourceMessageIndexAndContentDigest(id,index,digest);
        String ref;
        if(existing.isPresent())ref=existing.get().getId();
        else {ref="legacy_"+UUID.randomUUID();detailRefs.saveAndFlush(new HistoryDetailRef(ref,id,runs.get(id).getConversationId(),commit.getRevision(),index,
                DetailRefKind.MODEL_OUTPUT,"assistant",digest,message.content(),message.content(),message.content(),"",20,Instant.now(),Instant.now()));}
        if(summaries.findByRunIdOrderBySummaryVersionAsc(id).isEmpty())summaries.saveAndFlush(new ContextSummary(UUID.randomUUID().toString(),id,runs.get(id).getConversationId(),ContextSummaryKind.CHECKPOINT,
                index,index,json.writeValueAsString(Map.of("text",message.content())),"[]","[]","[]","[]","[]","digest",1,Instant.now()));
        return ref;
    }
    private void assertHidden(String id,String ref) throws Exception {
        String body=http.perform(get("/api/v1/runs/{id}/memory",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).path("catalog")).isEmpty();assertThat(json.readTree(body).path("summaries")).isEmpty();
        assertSearchAndDetailHidden(id,ref);
    }
    private void assertSearchAndDetailHidden(String id,String ref) throws Exception {
        String found=http.perform(post("/api/v1/runs/{id}/memory/search",id).contentType("application/json")
                .content("{\"query\":\"退货\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(found).path("matches")).isEmpty();
        String denied=http.perform(get("/api/v1/runs/{id}/memory/details/{ref}",id,ref)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(denied).doesNotContain(answer);
    }
    private void awaitState(String id,RunState state) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end && runs.get(id).getState()==RunState.RUNNING)Thread.sleep(10);
        assertThat(runs.get(id).getState()).as(runs.get(id).getOutputMessage()).isEqualTo(state);
    }
}
