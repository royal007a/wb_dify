package com.hify.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.domain.WorkflowVersion;
import com.hify.workflow.infrastructure.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-knowledge;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
class WorkflowKnowledgeIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired KnowledgeRetrievalPort retrieval;
    @Autowired WorkflowVersionRepository versions;

    @Test void publishedWorkflowAndOldConversationKeepArchivedCorpusAndReadableCitations() throws Exception {
        String base=base(), oldDocument=upload(base,"退货期限是七天。"), wid=workflow(base);
        JsonNode v1=publish(wid);String version=v1.path("id").asText();
        String originalDsl=versions.findById(version).orElseThrow().getDslJson();
        String agent=agent(wid), conversation=conversation(agent);
        JsonNode first=execute(version);assertThat(first.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(first.path("output").asText()).contains("七天");
        JsonNode citation=first.path("context").path("lookup.citations").get(0);
        String cid=citation.path("chunkId").asText(), digest=citation.path("digest").asText();
        http.perform(delete("/api/v1/documents/{id}",oldDocument)).andExpect(status().isOk());
        upload(base,"退货期限是十五天。");
        JsonNode old=execute(version);
        assertThat(old.path("output").asText()).contains("七天").doesNotContain("十五天");
        assertThat(retrieval.requireCanonicalChunk(cid,digest).content()).contains("七天");
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(cid,null)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(cid,"wrong")).isInstanceOf(BizException.class);
        JsonNode v2=publish(wid);
        assertThat(v2.path("checksum")).isNotEqualTo(v1.path("checksum"));
        assertThat(versions.findById(version).orElseThrow().getDslJson()).isEqualTo(originalDsl);
        assertThat(versions.findById(version).orElseThrow().getChecksum()).isEqualTo(v1.path("checksum").asText());
        assertThat(execute(v2.path("id").asText()).path("output").asText()).contains("十五天").doesNotContain("七天");
        // Re-publish the Agent, then prove the historical conversation still selects v1.
        http.perform(post("/api/v1/agents/{id}/publications",agent)).andExpect(status().isOk());
        assertThat(chat(conversation).path("outputMessage").asText()).contains("七天").doesNotContain("十五天");
        assertThat(chat(conversation(agent)).path("outputMessage").asText()).contains("十五天").doesNotContain("七天");
        JsonNode frozen=json.readTree(versions.findById(version).orElseThrow().getDslJson()).path("nodes");
        JsonNode snapshot=null;for(JsonNode node:frozen)if(node.path("type").asText().equals("KNOWLEDGE"))snapshot=node.path("config").path("knowledgeSnapshot");
        assertThat(snapshot).isNotNull();assertThat(snapshot.path("corpusVersionId").asText()).isNotBlank();
        assertThat(snapshot.path("manifestDigest").asText()).hasSize(64);
        JsonNode draft=body(http.perform(get("/api/v1/workflows/{id}",wid)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        for(JsonNode node:draft.path("nodes"))assertThat(node.path("config").has("knowledgeSnapshot")).isFalse();
        http.perform(delete("/api/v1/knowledge-bases/{id}",base)).andExpect(status().isOk());
        assertThat(execute(version).path("output").asText()).contains("七天");
        assertThat(retrieval.requireCanonicalChunk(cid,digest).content()).contains("七天");
        http.perform(post("/api/v1/workflows/{id}/versions",wid)).andExpect(status().isNotFound());
    }

    @Test void clientCannotChooseSnapshotAndLegacyKnowledgeVersionRequiresRepublish() throws Exception {
        String base=base(), wid=workflow(base);ObjectNode forged=graph(base);
        ((ObjectNode)forged.path("nodes").get(1).path("config")).set("knowledgeSnapshot",json.createObjectNode().put("corpusVersionId","arbitrary"));
        http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(forged))).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/workflows/{id}",wid).contentType("application/json").content(json.writeValueAsString(forged))).andExpect(status().isBadRequest());
        String legacy=json.writeValueAsString(graph(base)), id=UUID.randomUUID().toString();
        versions.saveAndFlush(new WorkflowVersion(id,wid,1,1,legacy,"legacy-checksum",Instant.now()));
        http.perform(post("/api/v1/workflow-versions/{id}/runs",id).contentType("application/json").content("{\"input\":\"退货\"}"))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,id)).isZero();
        assertThat(versions.findById(id).orElseThrow().getDslJson()).isEqualTo(legacy);
    }

    @Test void archivedUnpublishedChunkIsNotAnHistoricalCitation() throws Exception {
        String base=base(), document=upload(base,"从未发布的文档");
        JsonNode chunk=body(http.perform(get("/api/v1/documents/{id}/chunks",document)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").get(0);
        http.perform(delete("/api/v1/documents/{id}",document)).andExpect(status().isOk());
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(chunk.path("chunkId").asText(),chunk.path("digest").asText())).isInstanceOf(BizException.class);
    }

    @Test void emptyCorpusCanBeFrozenWithPortableTimestampBindings() throws Exception {
        String base=base();
        var snapshot=retrieval.freeze(base);
        assertThat(snapshot.chunkCount()).isZero();
        assertThat(retrieval.searchSnapshot(snapshot,"no documents",3)).isEmpty();
    }

    @Test void failedPublicationRollsBackAllNewCorporaAndVersionRows() throws Exception {
        String base=base();upload(base,"发布事务的原文");
        ObjectNode draft=graph(base);
        var nodes=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("nodes");
        ObjectNode second=((ObjectNode)nodes.get(1)).deepCopy();second.put("nodeKey","second");
        ((ObjectNode)second.path("config")).put("knowledgeBaseId","zz-missing");nodes.add(second);
        ((ObjectNode)draft.path("edges").get(1)).put("targetNodeKey","second");
        ((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("edges")).addObject().put("edgeKey","c").put("sourceNodeKey","second").put("targetNodeKey","end");
        String wid=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(draft)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        http.perform(post("/api/v1/workflows/{id}/versions",wid)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("select count(*) from knowledge_corpus_versions where knowledge_base_id=?",Integer.class,base)).isZero();
        assertThat(versions.countByWorkflowId(wid)).isZero();
    }

    @Test void frozenCorpusRejectsChangedCanonicalContentEvenWhenStoredDigestIsUnchanged() throws Exception {
        String base=base();upload(base,"真实原文：退货期限七天。");String v=publish(workflow(base)).path("id").asText();
        JsonNode before=execute(v);String chunk=before.path("context").path("lookup.citations").get(0).path("chunkId").asText();
        db.update("update document_chunks set content='被替换的原文' where id=?",chunk);
        JsonNode failed=execute(v);assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("errorMessage").asText()).contains("摘要");
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(chunk,null)).isInstanceOf(BizException.class);
    }

    @ParameterizedTest @ValueSource(strings={"member", "memberDigest", "manifest", "base"})
    void frozenCorpusRejectsIncompleteOrMismatchedManifest(String damage) throws Exception {
        String base=base();upload(base,"清单完整性：七天退货。");String v=publish(workflow(base)).path("id").asText();
        JsonNode dsl=json.readTree(versions.findById(v).orElseThrow().getDslJson());String corpus="";
        for(JsonNode node:dsl.path("nodes"))if(node.path("type").asText().equals("KNOWLEDGE"))corpus=node.path("config").path("knowledgeSnapshot").path("corpusVersionId").asText();
        assertThat(corpus).isNotBlank();
        switch(damage){
            case "member" -> db.update("delete from knowledge_corpus_version_chunks where corpus_version_id=?",corpus);
            case "memberDigest" -> db.update("update knowledge_corpus_version_chunks set content_digest=? where corpus_version_id=?","b".repeat(64),corpus);
            case "manifest" -> db.update("update knowledge_corpus_versions set manifest_digest=? where id=?","b".repeat(64),corpus);
            case "base" -> db.update("update knowledge_corpus_versions set knowledge_base_id=? where id=?",base(),corpus);
            default -> throw new AssertionError(damage);
        }
        JsonNode failed=execute(v);assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("nodes")).hasSize(2); // END must never run on partial evidence.
        assertThat(failed.path("errorMessage").asText()).containsAnyOf("摘要","清单");
    }

    protected String base() throws Exception {return body(http.perform(post("/api/v1/knowledge-bases").contentType("application/json")
            .content(json.writeValueAsString(Map.of("name","frozen-"+UUID.randomUUID(),"chunkSize",256,"chunkOverlap",16))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();}
    protected String upload(String base,String content) throws Exception {
        var file=new MockMultipartFile("file","policy.txt","text/plain",content.getBytes(StandardCharsets.UTF_8));
        String id=body(http.perform(multipart("/api/v1/knowledge-bases/{id}/documents",base).file(file)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).path("data").asText();
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){
            JsonNode doc=body(http.perform(get("/api/v1/documents/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            String state=doc.path("indexingState").asText();if(state.equals("DONE"))return id;
            assertThat(state).isNotEqualTo("FAILED");Thread.sleep(25);
        }throw new AssertionError("indexing timeout");
    }
    protected ObjectNode graph(String base) throws Exception {return (ObjectNode)json.readTree("""
            {"name":"snapshot-%s","schemaVersion":1,"nodes":[
            {"nodeKey":"start","type":"START","name":"start","config":{}},
            {"nodeKey":"lookup","type":"KNOWLEDGE","name":"lookup","config":{"knowledgeBaseId":"%s","query":"{{start.userMessage}}"}},
            {"nodeKey":"end","type":"END","name":"end","config":{"output":"{{lookup.citations}}"}}],
            "edges":[{"edgeKey":"a","sourceNodeKey":"start","targetNodeKey":"lookup"},{"edgeKey":"b","sourceNodeKey":"lookup","targetNodeKey":"end"}]}
            """.formatted(UUID.randomUUID(),base));}
    protected String workflow(String base) throws Exception {return body(http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(graph(base))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();}
    protected JsonNode publish(String id) throws Exception {return body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    protected JsonNode execute(String id) throws Exception {return body(http.perform(post("/api/v1/workflow-versions/{id}/runs",id).contentType("application/json").content("{\"input\":\"退货期限\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");}
    protected String agent(String wid) throws Exception {
        String aid=draftAgent(wid);
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isOk());return aid;
    }
    protected String draftAgent(String wid) throws Exception {
        String aid=body(http.perform(post("/api/v1/agents").contentType("application/json").content("""
                {"name":"frozen-agent-%s","instructions":"workflow","providerId":"mock","modelId":"hify-mock","temperature":0.2,"maxTokens":2048,"maxTurns":6,"maxContextTurns":10,"enabledTools":[],"enabled":true}
                """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        http.perform(put("/api/v1/agents/{id}/workflow-binding",aid).contentType("application/json").content(json.writeValueAsString(Map.of("workflowId",wid)))).andExpect(status().isOk());
        return aid;
    }
    private String conversation(String aid) throws Exception {return body(http.perform(post("/api/v1/conversations").contentType("application/json").content(json.writeValueAsString(Map.of("agentId",aid,"title","frozen"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();}
    private JsonNode chat(String cid) throws Exception {
        String id=body(http.perform(post("/api/v1/conversations/{id}/runs",cid).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"message\":\"退货期限\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){JsonNode run=body(http.perform(get("/api/v1/runs/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if(!run.path("state").asText().equals("RUNNING")){assertThat(run.path("state").asText()).isEqualTo("COMPLETED");return run;}Thread.sleep(25);}
        throw new AssertionError("run timeout");
    }
    protected JsonNode body(String raw) throws Exception {return json.readTree(raw);}
}
