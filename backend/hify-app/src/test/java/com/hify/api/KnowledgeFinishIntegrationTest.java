package com.hify.api;

import com.hify.agent.api.*;
import com.hify.application.RunApplicationService;
import com.hify.domain.*;
import com.hify.infra.*;
import com.hify.knowledge.application.KnowledgeRetrievalService;
import com.hify.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:knowledge-finish;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
class KnowledgeFinishIntegrationTest extends WorkflowKnowledgeIntegrationTest {
    @Autowired AgentService agentService;
    @Autowired AgentQueryService queries;
    @Autowired RunApplicationService runs;
    @Autowired ConversationRepository conversations;
    @Autowired RunEventRepository events;
    @Autowired RunCheckpointRepository checkpoints;
    @MockBean ModelClientFactory models;
    @SpyBean KnowledgeRetrievalService source;
    final AtomicInteger calls=new AtomicInteger();
    String answer;
    @BeforeEach void model(){calls.set(0);answer="退货期限是七天。[K1]";
        when(models.create(any())).thenReturn(request->{calls.incrementAndGet();return RuntimeMessage.assistant(answer);});}

    @Test void emptyBoundCorpusNeverCallsModelOrStoresAssistant() throws Exception {
        AgentRun run=chatWith(List.of(base()),"退货期限是多少");
        assertThat(run.getState()).isEqualTo(RunState.FAILED);
        assertThat(run.getTerminalReason()).isEqualTo("KNOWLEDGE_NO_EVIDENCE");
        assertThat(calls).hasValue(0);assertNoAssistant(run);
    }
    @Test void unrelatedVectorTopKDoesNotBecomeEvidence() throws Exception {
        String base=base();upload(base,"退货政策：未拆封商品七天内可以退货。");
        AgentRun run=chatWith(List.of(base),"CEO是谁？");
        assertThat(run.getTerminalReason()).isEqualTo("KNOWLEDGE_NO_EVIDENCE");
        assertThat(calls).hasValue(0);assertNoAssistant(run);
    }
    @Test void partialRetrievalFailureDoesNotSilentlyFallBackToOtherSources() throws Exception {
        String base=base(), unavailable=base();upload(base,"退货期限是七天。");
        var failing=retrieval.freeze(unavailable);
        doThrow(new IllegalStateException("private upstream secret")).when(source).searchRevision(eq(failing.id()),anyString(),anyInt());
        AgentRun run=chatWith(List.of(base,unavailable),"退货期限");
        assertThat(run.getTerminalReason()).isEqualTo("KNOWLEDGE_RETRIEVAL_FAILED");
        assertThat(run.getOutputMessage()).doesNotContain("private upstream secret");
        assertThat(events.findByRunIdOrderByIdAsc(run.getId()).stream().map(RunEvent::getPayload)
                .collect(java.util.stream.Collectors.joining("\n"))).doesNotContain("private upstream secret");
        assertThat(calls).hasValue(0);assertNoAssistant(run);
    }
    @Test void missingOrInventedFinalReferencesRequireClarification() throws Exception {
        String base=base();upload(base,"退货期限是七天。");
        for(String invalid:List.of("所有商品一年内都能退货", "退货期限是七天。[K999]")){
            answer=invalid;AgentRun run=chatWith(List.of(base),"退货期限");
            assertThat(run.getState()).as("%s: %s",run.getTerminalReason(),run.getOutputMessage()).isEqualTo(RunState.NEEDS_INPUT);assertNoAssistant(run);
            var checkpoint=checkpoints.findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(run.getId()).orElseThrow();
            assertThat(checkpoint.getContextJson()).contains("knowledge:references","OPEN");
            assertThat(events.findByRunIdOrderByIdAsc(run.getId())).noneMatch(e->e.getEventType().equals("message.delta"));
        }
    }
    @Test void validReferenceChecksSourcesNotSemanticTruth() throws Exception {
        String base=base();upload(base,"退货期限是七天。");
        AgentRun run=chatWith(List.of(base),"退货期限");
        assertThat(run.getState()).isEqualTo(RunState.COMPLETED);assertThat(calls).hasValue(1);
        String terminal=events.findByRunIdOrderByIdAsc(run.getId()).stream().filter(e->e.getEventType().equals("run.completed")).findFirst().orElseThrow().getPayload();
        assertThat(terminal).contains("SOURCE_REFERENCES_ONLY","semanticClaimsVerified");
        assertThat(json.readTree(terminal).path("semanticClaimsVerified").asBoolean(true)).isFalse();
    }
    @Test void unboundOrdinaryChatDoesNotRequireKnowledgeCitations() throws Exception {
        answer="你好。";AgentRun run=chatWith(List.of(),"你好");
        assertThat(run.getState()).isEqualTo(RunState.COMPLETED);assertThat(calls).hasValue(1);
    }
    @Test void workflowEmptyKnowledgeDoesNotReachEnd() throws Exception {
        var result=execute(publish(workflow(base())).path("id").asText());
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("errorMessage").asText()).contains("KNOWLEDGE_NO_EVIDENCE");
        assertThat(result.path("nodes")).hasSize(2);
    }
    @Test void resumeKeepsFrozenKReferencesAfterArchiveAndDoesNotRetrieveAgain() throws Exception {
        String base=base(), document=upload(base,"退货期限是七天。");
        answer="没有引用";AgentRun first=chatWith(List.of(base),"退货期限");
        assertThat(first.getState()).isEqualTo(RunState.NEEDS_INPUT);
        var checkpoint=checkpoints.findTopByRunIdAndRestorableTrueOrderBySequenceNoDesc(first.getId()).orElseThrow();
        var context=json.readValue(checkpoint.getContextJson(),com.hify.runtime.state.ExecutionContextState.class);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/documents/{id}",document))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        upload(base,"退货期限是十五天。");clearInvocations(source);
        answer="退货期限是七天。[K1]";
        String next=runs.create(first.getConversationId(),UUID.randomUUID().toString(),"请按原来的知识引用回答",
                new RunApplicationService.ResumeRequest(first.getId(),context.openBlockingGapIds())).run().getId();
        AgentRun resumed=await(next);assertThat(resumed.getState()).as(resumed.getOutputMessage()).isEqualTo(RunState.COMPLETED);
        assertThat(resumed.getOutputMessage()).contains("七天");
        verify(source,never()).searchRevision(anyString(),anyString(),anyInt());
        verify(source).requireCanonicalChunk(eq(context.evidence().get(0).sourceRef().split(":",3)[2]),eq(context.evidence().get(0).valueDigest()));
    }
    private AgentRun chatWith(List<String> bases,String input) throws Exception {
        String aid=agentService.create(new AgentUpsertRequest("evidence-"+UUID.randomUUID(),"","answer with sources","mock","hify-mock",0.2,2048,6,10,List.of(),true));
        if(!bases.isEmpty())agentService.replaceKnowledge(aid,new AgentKnowledgeBindingRequest(bases.stream().map(b->new AgentKnowledgeBindingInput(b,3,0)).toList()));
        agentService.publish(aid);String cid=UUID.randomUUID().toString();
        conversations.saveAndFlush(new Conversation(cid,aid,queries.requirePublished(aid).versionId(),"knowledge",Instant.now()));
        String id=runs.create(cid,UUID.randomUUID().toString(),input).run().getId();
        return await(id);
    }
    private AgentRun await(String id) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){var run=runs.get(id);if(run.getState()!=RunState.RUNNING)return run;Thread.sleep(10);}
        throw new AssertionError("run timeout");
    }
    private void assertNoAssistant(AgentRun run){assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,run.getConversationId())).isZero();}
}
