package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.*;
import com.hify.application.RunApplicationService;
import com.hify.domain.Conversation;
import com.hify.domain.RunState;
import com.hify.infra.ConversationRepository;
import com.hify.infra.RunEventRepository;
import com.hify.workflow.api.*;
import com.hify.workflow.application.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-settlement;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","hify.run-timeout=120s"})
class WorkflowSettlementIntegrationTest {
    @Autowired RunApplicationService service;
    @Autowired AgentService agents;
    @Autowired AgentQueryService agentQueries;
    @Autowired WorkflowApplicationService workflows;
    @Autowired ConversationRepository conversations;
    @Autowired RunEventRepository events;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc http;
    @SpyBean WorkflowCapabilityAdapter executor;
    @SpyBean com.hify.application.RunEventBroker broker;

    @ParameterizedTest @CsvSource({"false,true","true,true","false,false","true,false"})
    void executionFactAndDeliveryDecisionAreBothProjected(boolean fail,boolean cancel) throws Exception {
        String suffix=UUID.randomUUID().toString();
        // Publish a valid corpus, then corrupt it only after publication to produce a real node failure.
        // Missing bases are now rejected at publication, before this settlement path is reachable.
        String base=fail?json.readTree(http.perform(post("/api/v1/knowledge-bases").contentType("application/json")
                .content(json.writeValueAsString(Map.of("name","settlement-"+suffix,"chunkSize",256,"chunkOverlap",16))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText():"";
        String dsl=fail?"""
                {"name":"failure-%s","schemaVersion":1,"nodes":[
                {"nodeKey":"start","name":"start","type":"START","config":{}},
                {"nodeKey":"lookup","name":"lookup","type":"KNOWLEDGE","config":{"knowledgeBaseId":"%s","query":"{{start.userMessage}}"}},
                {"nodeKey":"end","name":"end","type":"END","config":{"output":"not reached"}}],
                "edges":[{"edgeKey":"a","sourceNodeKey":"start","targetNodeKey":"lookup"},
                {"edgeKey":"b","sourceNodeKey":"lookup","targetNodeKey":"end"}]}
                """.formatted(suffix,base):"""
                {"name":"success-%s","schemaVersion":1,"nodes":[
                {"nodeKey":"start","name":"start","type":"START","config":{}},
                {"nodeKey":"end","name":"end","type":"END","config":{"output":"computed"}}],
                "edges":[{"edgeKey":"a","sourceNodeKey":"start","targetNodeKey":"end"}]}
                """.formatted(suffix);
        String workflow=workflows.create(json.readValue(dsl,WorkflowDraftRequest.class));
        String version=workflows.publish(workflow).id();
        String agent=agents.create(new AgentUpsertRequest("settlement-"+suffix,"","execute workflow","mock","hify-mock",0.2,2048,6,10,List.of(),true));
        agents.replaceWorkflow(agent,new AgentWorkflowBindingRequest(workflow));agents.publish(agent);
        if(fail)assertThat(db.update("update knowledge_corpus_versions set manifest_digest=? where knowledge_base_id=?",
                "b".repeat(64),base)).isEqualTo(1);
        String conversation=UUID.randomUUID().toString();
        conversations.saveAndFlush(new Conversation(conversation,agent,agentQueries.requirePublished(agent).versionId(),"settlement",Instant.now()));
        var returned=new CountDownLatch(1);var release=new CountDownLatch(1);
        var projectionWritten=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        if(!fail)doAnswer(invocation->{
            Object value=invocation.callRealMethod();
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            projectionWritten.countDown();assertThat(allowCommit.await(10,TimeUnit.SECONDS)).isTrue();return value;
        }).when(broker).publish(anyString(),eq("workflow.completed"),anyMap());
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();returned.countDown();
            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return result;
        }).when(executor).execute(eq(version),eq("input"),any());
        String run=service.create(conversation,suffix,"input").run().getId();
        try {
            assertThat(returned.await(10,TimeUnit.SECONDS)).isTrue();
            String executionState=fail?"FAILED":"SUCCEEDED";
            assertThat(db.queryForObject("select status from workflow_runs where workflow_version_id=?",String.class,version)).isEqualTo(executionState);
            long cursor=events.findByRunIdOrderByIdAsc(run).stream().filter(e->e.getEventType().equals("workflow.started")).findFirst().orElseThrow().getId();
            if(cancel)http.perform(post("/api/v1/runs/{id}/cancellations",run)).andExpect(status().isAccepted());
            release.countDown();
            if(!fail){
                assertThat(projectionWritten.await(10,TimeUnit.SECONDS)).isTrue();
                // A second connection cannot see any portion of the uncommitted settlement.
                assertThat(db.queryForObject("select state from agent_runs where id=?",String.class,run)).isEqualTo("RUNNING");
                assertThat(events.findByRunIdOrderByIdAsc(run)).noneMatch(e->e.getEventType().equals("workflow.completed")||e.getEventType().equals("run.completed")||e.getEventType().equals("run.cancelled"));
                assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,conversation)).isZero();
                allowCommit.countDown();
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!service.get(run).getState().terminal()&&System.nanoTime()<end)Thread.sleep(10);
            RunState expected=cancel?RunState.CANCELLED:fail?RunState.FAILED:RunState.COMPLETED;
            assertThat(service.get(run).getState()).isEqualTo(expected);
            if(fail&&!cancel)assertThat(service.get(run).getTerminalReason()).isEqualTo("WORKFLOW_ERROR");
            assertThat(db.queryForObject("select status from workflow_runs where workflow_version_id=?",String.class,version)).isEqualTo(executionState);
            assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,conversation))
                    .isEqualTo(expected==RunState.COMPLETED?1:0);
            String projection=fail?"workflow.failed":"workflow.completed";
            var projected=events.findByRunIdOrderByIdAsc(run).stream().filter(e->e.getEventType().equals(projection)).toList();
            assertThat(projected).hasSize(1);
            var payload=json.readTree(projected.get(0).getPayload());
            assertThat(payload.path("version").asInt()).isEqualTo(2);
            assertThat(payload.path("executionState").asText()).isEqualTo(executionState);
            assertThat(payload.path("runState").asText()).isEqualTo(expected.name());
            assertThat(payload.path("runTerminalReason").asText()).isEqualTo(service.get(run).getTerminalReason());
            assertThat(payload.path("assistantCommitted").asBoolean()).isEqualTo(expected==RunState.COMPLETED);
            String terminal=cancel?"run.cancelled":fail?"run.failed":"run.completed";
            var stream=http.perform(get("/api/v1/runs/{id}/events/stream",run).header("Last-Event-ID",cursor))
                    .andExpect(request().asyncStarted()).andReturn();
            String replay=http.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(replay).contains("event:"+projection,"event:"+terminal).doesNotContain("event:workflow.started");
            assertThat(events.findByRunIdOrderByIdAsc(run)).filteredOn(e->e.getEventType().equals(terminal)).hasSize(1);
            service.cancel(run); // An already committed outcome is never retroactively changed.
            assertThat(service.get(run).getState()).isEqualTo(expected);
        } finally {release.countDown();allowCommit.countDown();}
    }
}
