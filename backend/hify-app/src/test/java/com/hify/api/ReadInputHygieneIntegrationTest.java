package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.IntentRoutingApplicationService;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.knowledge.api.KnowledgeCorpusSnapshot;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.memory.HistoryRecallService;
import com.hify.runtime.ModelClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:read-input-hygiene;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
@AutoConfigureMockMvc @DirtiesContext
class ReadInputHygieneIntegrationTest {
    static final String NUL=String.valueOf((char)0);
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired KnowledgeRetrievalPort retrieval;
    @Autowired HistoryRecallService recall;
    @Autowired IntentRoutingApplicationService intents;
    @SpyBean ModelClientFactory models;

    @Test void activeKnowledgeQueryRejectsNulBeforeRetrievalWithPositiveMatch()throws Exception {
        Fixture fixture=indexed();String url="/api/v1/knowledge-bases/"+fixture.base()+"/retrieval-tests";
        JsonNode positive=call(post(url).contentType("application/json").content(query(fixture.text())),200).path("data");
        assertThat(positive).isNotEmpty();
        assertThat(positive.get(0).path("documentId").asText()).isEqualTo(fixture.document());
        assertThat(positive.get(0).path("content").asText()).contains(fixture.text());
        var before=counts();clearInvocations(models);
        for(String text:badTexts(fixture.text())){
            call(post(url).contentType("application/json").content(query(text)),400);
            rejected(()->retrieval.search(fixture.base(),text,3));
        }
        rejected(()->retrieval.search(fixture.base()+NUL,fixture.text(),3));
        assertThat(counts()).isEqualTo(before);verifyNoInteractions(models);
    }

    @Test void frozenKnowledgeQueryRejectsNulOnBothPublicSearchEntrypoints()throws Exception {
        Fixture fixture=indexed();KnowledgeCorpusSnapshot snapshot=retrieval.freeze(fixture.base());
        assertThat(retrieval.searchRevision(snapshot.id(),fixture.text(),3)).extracting(KnowledgeCitation::documentId).contains(fixture.document());
        assertThat(retrieval.searchSnapshot(snapshot,fixture.text(),3)).extracting(KnowledgeCitation::documentId).contains(fixture.document());
        var before=counts();clearInvocations(models);
        for(String text:badTexts(fixture.text())){
            rejected(()->retrieval.searchRevision(snapshot.id(),text,3));
            rejected(()->retrieval.searchSnapshot(snapshot,text,3));
        }
        rejected(()->retrieval.searchRevision(snapshot.id()+NUL,fixture.text(),3));
        for(KnowledgeCorpusSnapshot bad:List.of(
                new KnowledgeCorpusSnapshot(snapshot.id()+NUL,snapshot.knowledgeBaseId(),snapshot.revisionNo(),snapshot.manifestDigest(),snapshot.chunkCount()),
                new KnowledgeCorpusSnapshot(snapshot.id(),snapshot.knowledgeBaseId()+NUL,snapshot.revisionNo(),snapshot.manifestDigest(),snapshot.chunkCount()),
                new KnowledgeCorpusSnapshot(snapshot.id(),snapshot.knowledgeBaseId(),snapshot.revisionNo(),snapshot.manifestDigest()+NUL,snapshot.chunkCount())))
            rejected(()->retrieval.searchSnapshot(bad,fixture.text(),3));
        assertThat(counts()).isEqualTo(before);verifyNoInteractions(models);
    }

    @Test void memoryQueryAndEntityRejectNulWithoutHidingValidHistory()throws Exception {
        String marker="memo-"+UUID.randomUUID();
        String conversation=call(post("/api/v1/conversations").contentType("application/json")
                .content("{\"agentId\":\"demo-agent\",\"title\":\"Read admission\"}"),201).path("id").asText();
        String run=call(post("/api/v1/conversations/"+conversation+"/runs").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(json.createObjectNode().put("message",marker+" 请计算21*2").toString()),202).path("id").asText();
        assertThat(awaitField("/api/v1/runs/"+run,"state","RUNNING").path("state").asText()).isEqualTo("COMPLETED");
        String url="/api/v1/runs/"+run+"/memory/search";
        JsonNode positive=call(post(url).contentType("application/json").content(query(marker)),200);
        assertThat(positive.path("matches")).isNotEmpty();
        assertThat(positive.path("matches").toString()).contains(marker);
        assertThat(recall.search(run,search(marker,null)).matches()).anySatisfy(match->assertThat(match.preview()).contains(marker));
        var before=counts();clearInvocations(models);
        for(String text:badTexts(marker)){
            call(post(url).contentType("application/json").content(query(text)),400);
            rejected(()->recall.search(run,search(text,null)));
        }
        call(post(url).contentType("application/json").content(json.createObjectNode().put("query",marker).put("entity","bad"+NUL).toString()),400);
        rejected(()->recall.search(run,search(marker,"bad"+NUL)));
        rejected(()->recall.search(run+NUL,search(marker,null)));
        assertThat(counts()).isEqualTo(before);verifyNoInteractions(models);
    }

    @Test void intentAgentAndInputRejectNulBeforeModelFactory()throws Exception {
        clearInvocations(models);
        call(post("/api/v1/intent-decisions").contentType("application/json")
                .content("{\"agentId\":\"demo-agent\",\"input\":\"classify nebulous violet request\"}"),200);
        verify(models,atLeastOnce()).create(any()); // Positive control: this route really can reach the Mock model.
        clearInvocations(models);var before=counts();
        for(String text:badTexts("demo-agent")){
            call(post("/api/v1/intent-decisions").contentType("application/json")
                    .content(json.createObjectNode().put("agentId",text).put("input","hello").toString()),400);
            rejected(()->intents.decide(text,"hello"));
        }
        for(String text:badTexts("classify nebulous violet request")){
            call(post("/api/v1/intent-decisions").contentType("application/json")
                    .content(json.createObjectNode().put("agentId","demo-agent").put("input",text).toString()),400);
            rejected(()->intents.decide("demo-agent",text));
        }
        assertThat(counts()).isEqualTo(before);verifyNoInteractions(models);
        assertThat(call(post("/api/v1/intent-decisions").contentType("application/json")
                .content("{\"agentId\":\"demo-agent\",\"input\":\"请计算 12.5*4\"}"),200).path("intent").asText()).isEqualTo("calculate");
    }

    private Fixture indexed()throws Exception {
        String text="retrieval-"+UUID.randomUUID();
        String base=call(post("/api/v1/knowledge-bases").contentType("application/json")
                .content(json.createObjectNode().put("name",text).toString()),201).path("data").asText();
        String doc=call(multipart("/api/v1/knowledge-bases/"+base+"/documents").file(new MockMultipartFile(
                "file","admission.txt","text/plain",text.getBytes(StandardCharsets.UTF_8))),202).path("data").asText();
        JsonNode done=awaitField("/api/v1/documents/"+doc,"indexingState","PENDING","PROCESSING").path("data");
        assertThat(done.path("indexingState").asText()).isEqualTo("DONE");
        assertThat(done.path("chunkCount").asInt()).isPositive();return new Fixture(base,doc,text);
    }
    private JsonNode awaitField(String path,String field,String... pending)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(System.nanoTime()<deadline){JsonNode value=call(get(path),200);JsonNode data=value.has("data")?value.path("data"):value;
            if(!List.of(pending).contains(data.path(field).asText()))return value;Thread.sleep(10);}
        throw new AssertionError("Fixture did not settle: "+field);
    }
    private Map<String,Long> counts(){return Map.of("runs",count("agent_runs"),"messages",count("chat_messages"),
            "tasks",count("document_index_tasks"),"documents",count("knowledge_documents"),"snapshots",count("knowledge_corpus_versions"));}
    private long count(String table){return db.queryForObject("select count(*) from "+table,Long.class);}
    private List<String> badTexts(String text){return List.of("invalid"+NUL+text,NUL+text,text+NUL);}
    private String query(String text){return json.createObjectNode().put("query",text).toString();}
    private HistoryRecallService.SearchQuery search(String query,String entity){return new HistoryRecallService.SearchQuery(query,null,null,null,entity,8);}
    private void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BizException.class,
            e->{assertThat(e.errorCode()).isEqualTo(ErrorCode.PARAM_ERROR);assertThat(e.getMessage()).doesNotContain(NUL,"invalid");});}
    private JsonNode call(MockHttpServletRequestBuilder request,int status)throws Exception {
        var response=http.perform(request).andExpect(status().is(status));
        if(status==400)response.andExpect(jsonPath("$.code").value(40000));
        JsonNode body=json.readTree(response.andReturn().getResponse().getContentAsString());
        if(status==400)assertThat(body.path("message").asText()).doesNotContain(NUL,"invalid");return body;
    }
    private record Fixture(String base,String document,String text){}
}
