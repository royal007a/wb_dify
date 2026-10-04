package com.hify.api;

import com.fasterxml.jackson.databind.node.*;
import com.hify.agent.api.*;
import com.hify.common.BizException;
import com.hify.knowledge.application.KnowledgeRetrievalService;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.application.WorkflowApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.TestPropertySource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-knowledge-review;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
class WorkflowKnowledgeReviewTest extends WorkflowKnowledgeIntegrationTest {
    @Autowired AgentService agents;
    @Autowired WorkflowApplicationService workflows;
    @SpyBean KnowledgeRetrievalService source;
    @SpyBean JdbcTemplate jdbcSpy;

    @Test void agentPinnedChecksumRejectsAChangedButInternallyValidWorkflow() throws Exception {
        String base=base();upload(base,"退货期限是七天。");String wid=workflow(base);
        var published=publish(wid);String version=published.path("id").asText();
        String original=versions.findById(version).orElseThrow().getDslJson();
        String aid=agent(wid);
        String cid=body(http.perform(post("/api/v1/conversations").contentType("application/json")
                .content(json.writeValueAsString(Map.of("agentId",aid,"title","checksum fixture")))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
        var before=runToTerminal(cid);
        assertThat(before.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(before.path("outputMessage").asText()).contains("七天");
        ObjectNode altered=(ObjectNode)json.readTree(original);
        for(var n:altered.path("nodes"))if(n.path("type").asText().equals("END"))
            ((ObjectNode)n.path("config")).put("output","changed-dsl-fixture");
        String changed=json.writeValueAsString(altered);
        assertThat(sha(changed)).isNotEqualTo(published.path("checksum").asText());
        db.update("update workflow_versions set dsl_json=?,checksum=? where id=?",changed,sha(changed),version);
        // Positive counterexample: row checksum and DSL are internally valid, direct version execution works.
        assertThat(execute(version).path("output").asText()).isEqualTo("changed-dsl-fixture");
        int workflowCount=db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,version);
        int nodeCount=db.queryForObject("select count(*) from workflow_node_runs",Integer.class);
        int assistantCount=db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,cid);
        var failed=runToTerminal(cid);
        assertThat(failed.path("state").asText()).isEqualTo("FAILED");
        assertThat(failed.path("terminalReason").asText()).isEqualTo("WORKFLOW_ERROR");
        assertThat(failed.path("agentVersionId")).isEqualTo(before.path("agentVersionId"));
        assertThat(db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,version)).isEqualTo(workflowCount);
        assertThat(db.queryForObject("select count(*) from workflow_node_runs",Integer.class)).isEqualTo(nodeCount);
        assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,cid)).isEqualTo(assistantCount);
        // Restore fixture to show rejection did not disable an otherwise valid old conversation.
        db.update("update workflow_versions set dsl_json=?,checksum=? where id=?",original,published.path("checksum").asText(),version);
        assertThat(runToTerminal(cid).path("state").asText()).isEqualTo("COMPLETED");
    }

    @Test void indexingSuccessIncludesTheActualVectorStorage() throws Exception {
        String base=base(),doc=upload(base,"index-positive-fixture 七天退货");
        int count=db.queryForObject("select count(*) from document_chunks where document_id=?",Integer.class,doc);
        assertThat(count).isPositive();
        assertThat(db.queryForObject("select count(*) from document_chunks where document_id=? and embedding_text is not null",Integer.class,doc)).isEqualTo(count);
        if(Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL"))))
            assertThat(db.queryForObject("select count(*) from document_chunks where document_id=? and embedding is not null",Integer.class,doc)).isEqualTo(count);
        assertThat(db.queryForObject("select state from document_index_tasks where document_id=?",String.class,doc)).isEqualTo("SUCCEEDED");
    }

    private com.fasterxml.jackson.databind.JsonNode runToTerminal(String cid) throws Exception {
        String id=body(http.perform(post("/api/v1/conversations/{id}/runs",cid).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content("{\"message\":\"退货期限\"}")).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            var value=body(http.perform(get("/api/v1/runs/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if(!value.path("state").asText().equals("RUNNING"))return value;
            Thread.sleep(10);
        }
        throw new AssertionError("checksum fixture run timeout");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void legacyGraphCannotPublishAgentEvenWithARealClientChosenCorpus(boolean forged) throws Exception {
        String base=base(), wid=workflow(base), version=publish(wid).path("id").asText(), aid=draftAgent(wid);
        ObjectNode old=(ObjectNode)json.readTree(versions.findById(version).orElseThrow().getDslJson());
        old.remove("publication"); // The old Draft DTO could store config, but never this server-owned root field.
        if(!forged)for(var n:old.path("nodes"))((ObjectNode)n.path("config")).remove("knowledgeSnapshot");
        String legacy=json.writeValueAsString(old);
        db.update("update workflow_versions set dsl_json=?,checksum=? where id=?",legacy,sha(legacy),version);
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isConflict());
        http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content("{\"input\":\"退货\"}"))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from agent_versions where agent_id=?",Integer.class,aid)).isZero();
        assertThat(db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,version)).isZero();
        assertThat(versions.findById(version).orElseThrow().getDslJson()).isEqualTo(legacy);
        publish(wid); // Required order: publish Workflow, then Agent; old versions are never repaired in-place.
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isOk());
    }

    @Test void storedDslChecksumIsCheckedBeforeBindingOrExecution() throws Exception {
        String wid=workflow(base()), version=publish(wid).path("id").asText(), aid=draftAgent(wid);
        ObjectNode changed=(ObjectNode)json.readTree(versions.findById(version).orElseThrow().getDslJson());
        changed.put("description","changed without updating checksum");
        db.update("update workflow_versions set dsl_json=? where id=?",json.writeValueAsString(changed),version);
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isConflict());
        http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content("{\"input\":\"退货\"}"))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,version)).isZero();
    }

    @Test void agentAndWorkflowPublishAcquireBasesInTheSameOrder() throws Exception {
        List<String> bases=new ArrayList<>(List.of(base(),base()));Collections.sort(bases);
        String a=bases.get(0),b=bases.get(1), wid=workflow(a);publish(wid);String aid=draftAgent(wid);
        agents.replaceKnowledge(aid,new AgentKnowledgeBindingRequest(List.of(
                new AgentKnowledgeBindingInput(b,3,0),new AgentKnowledgeBindingInput(a,3,1))));
        ObjectNode graph=graph(a);ArrayNode ns=(ArrayNode)graph.path("nodes");
        ObjectNode second=((ObjectNode)ns.get(1)).deepCopy();second.put("nodeKey","second");
        ((ObjectNode)second.path("config")).put("knowledgeBaseId",b);ns.add(second);
        ((ObjectNode)graph.path("edges").get(1)).put("targetNodeKey","second");
        ((ArrayNode)graph.path("edges")).addObject().put("edgeKey","c").put("sourceNodeKey","second").put("targetNodeKey","end");
        workflows.update(wid,json.treeToValue(graph,WorkflowDraftRequest.class));
        CountDownLatch agentHoldsA=new CountDownLatch(1), workflowWantsA=new CountDownLatch(1), releaseAgent=new CountDownLatch(1);
        List<String> order=new CopyOnWriteArrayList<>();AtomicReference<Thread> owner=new AtomicReference<>();
        doAnswer(invocation->{
            String id=invocation.getArgument(0);
            if(Thread.currentThread()==owner.get()){
                order.add(id);if(order.size()==1)assertThat(id).isEqualTo(a);
                Object result=invocation.callRealMethod();
                if(id.equals(a)){agentHoldsA.countDown();assertThat(releaseAgent.await(10,TimeUnit.SECONDS)).isTrue();}
                return result;
            }
            if(id.equals(a))workflowWantsA.countDown();
            return invocation.callRealMethod();
        }).when(source).freeze(anyString());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> first=pool.submit(()->{owner.set(Thread.currentThread());return agents.publish(aid);});
            assertThat(agentHoldsA.await(5,TimeUnit.SECONDS)).isTrue();
            Future<?> secondPublish=pool.submit(()->workflows.publish(wid));
            assertThat(workflowWantsA.await(5,TimeUnit.SECONDS)).isTrue();
            if(Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")))){
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int waiting=0;
                while(System.nanoTime()<deadline&&waiting==0){
                    waiting=db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%knowledge_bases%' AND pid<>pg_backend_pid()",Integer.class);
                    if(waiting==0)Thread.sleep(10);
                }
                assertThat(waiting).as("real PostgreSQL publisher waiting on the first base lock").isPositive();
            }
            assertThat(secondPublish.isDone()).isFalse();releaseAgent.countDown();
            first.get(10,TimeUnit.SECONDS);secondPublish.get(10,TimeUnit.SECONDS);
            assertThat(order).containsExactly(a,b);
        } finally {releaseAgent.countDown();pool.shutdownNow();}
    }

    @Test void verificationAndRankingUseOneRepeatableReadSnapshot() throws Exception {
        String base=base();upload(base,"退货期限是七天。");var snapshot=retrieval.freeze(base);
        CountDownLatch read=new CountDownLatch(1), changed=new CountDownLatch(1);AtomicBoolean once=new AtomicBoolean();
        doAnswer(invocation->{
            Object rows=invocation.callRealMethod();
            if(once.compareAndSet(false,true)){
                assertThat(db.execute((ConnectionCallback<Integer>)Connection::getTransactionIsolation))
                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                read.countDown();assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            }return rows;
        }).when(jdbcSpy).query(contains("JOIN knowledge_corpus_versions v"),any(RowMapper.class),eq(snapshot.id()));
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            var search=pool.submit(()->retrieval.searchSnapshot(snapshot,"退货期限",3));
            assertThat(read.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(db.update("update document_chunks set content=? where knowledge_base_id=?","未经验证的新内容",base)).isEqualTo(1);
            changed.countDown();
            assertThat(search.get(10,TimeUnit.SECONDS)).singleElement().satisfies(c->assertThat(c.content()).contains("七天"));
            assertThatThrownBy(()->retrieval.searchSnapshot(snapshot,"退货期限",3)).isInstanceOf(BizException.class);
        } finally {changed.countDown();pool.shutdownNow();}
    }

    @Test void dialectLookupReusesTheTransactionConnection() throws Exception {
        var snapshot=retrieval.freeze(base());AtomicBoolean checked=new AtomicBoolean();
        doAnswer(invocation->{
            ConnectionCallback<?> callback=invocation.getArgument(0);
            return dbExecuteChecked(callback,checked);
        }).when(jdbcSpy).execute(any(ConnectionCallback.class));
        retrieval.searchSnapshot(snapshot,"empty",3);
        assertThat(checked).isTrue();
    }
    private Object dbExecuteChecked(ConnectionCallback<?> callback,AtomicBoolean checked) throws Exception {
        Connection connection=DataSourceUtils.getConnection(db.getDataSource());
        try {
            assertThat(DataSourceUtils.isConnectionTransactional(connection,db.getDataSource())).isTrue();
            checked.set(true);return callback.doInConnection(connection);
        } finally {DataSourceUtils.releaseConnection(connection,db.getDataSource());}
    }
    private String sha(String value) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
