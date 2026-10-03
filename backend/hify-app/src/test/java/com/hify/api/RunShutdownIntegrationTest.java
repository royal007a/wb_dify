package com.hify.api;

import com.hify.HifyApplication;
import com.hify.agent.api.AgentQueryService;
import com.hify.agent.api.AgentService;
import com.hify.agent.api.AgentUpsertRequest;
import com.hify.agent.api.AgentWorkflowBindingRequest;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.application.WorkflowApplicationService;
import com.hify.application.RunApplicationService;
import com.hify.common.ExecutionCancelledException;
import com.hify.domain.Conversation;
import com.hify.domain.RunState;
import com.hify.infra.ConversationRepository;
import com.hify.runtime.ModelClient;
import com.hify.runtime.ModelClientFactory;
import com.hify.runtime.RuntimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real context.close(), real runExecutor, fresh context on the same isolated database. */
@org.junit.jupiter.api.Timeout(45)
class RunShutdownIntegrationTest {
    @Test void computedModelResultCommitsDuringRealShutdownAndIsNotReplayed() throws Exception {
        verifyComputedResultShutdown(database());
    }
    @Test void computedClarificationCommitsDuringShutdownWithoutRestartingTheLoop() throws Exception {
        verifyComputedResultShutdown(database(),true);
    }

    static void verifyComputedResultShutdown(String url) throws Exception {
        verifyComputedResultShutdown(url,false);
    }
    private static void verifyComputedResultShutdown(String url,boolean clarify) throws Exception {
        var computed=new CountDownLatch(1); var release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        String run; int completedCalls;
        RunState expected=clarify?RunState.NEEDS_INPUT:RunState.COMPLETED;
        String terminalEvent=clarify?"run.needs_input":"run.completed";
        try(var first=start(url,request->{
            int call=calls.incrementAndGet();
            return clarify?RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall("missing-"+call,"calculator",java.util.Map.of())))
                    :RuntimeMessage.assistant("already computed");
        },null,computed,release)) {
            var service=first.getBean(RunApplicationService.class);
            run=service.create(conversation(first),"completed-before-close","hello").run().getId();
            assertThat(computed.await(10,TimeUnit.SECONDS)).isTrue();
            completedCalls=calls.get();
            String id=run;
            // Only hold destruction until the worker returns; do not replace executor shutdown.
            var pool=first.getBean("runExecutor",org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class);
            first.addApplicationListener(event->{
                if(event instanceof org.springframework.context.event.ContextClosedEvent) {
                    assertThat(first.getBean(com.hify.common.ExecutionLifecycle.class).isStopping()).isTrue();
                    release.countDown();
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(pool.getActiveCount()>0 && System.nanoTime()<end) {
                        try {Thread.sleep(10);} catch(InterruptedException failure){throw new AssertionError(failure);}
                    }
                    assertThat(pool.getActiveCount()).as("computed result returned before persistence destruction: %s",id).isZero();
                }
            });
        } finally {release.countDown();}
        JdbcTemplate db=database(url);
        assertThat(db.queryForObject("select state from agent_runs where id=?",String.class,run)).isEqualTo(expected.name());
        assertThat(db.queryForList("select event_type from run_events where run_id=?",String.class,run))
                .contains(terminalEvent).doesNotContain("run.interrupted");
        if(!clarify)assertThat(db.queryForList("select event_type from run_events where run_id=?",String.class,run)).contains("message.delta");
        try(var second=start(url,request->{calls.incrementAndGet();return RuntimeMessage.assistant("duplicate");})) {
            assertThat(second.getBean(RunApplicationService.class).get(run).getState()).isEqualTo(expected);
            assertThat(calls).hasValue(completedCalls);
            assertThat(db.queryForObject("select count(*) from chat_messages where role='assistant'",Integer.class)).isEqualTo(clarify?0:1);
            assertThat(db.queryForObject("select count(*) from run_events where run_id=? and event_type=?",Integer.class,run,terminalEvent)).isEqualTo(1);
        }
    }

    @Test void startupInterruptsOldWorkflowRowsButNeverNewlyAcceptedExecutions() throws Exception {
        String url=database(),oldRun=UUID.randomUUID().toString();
        try(var first=start(url,request->new RuntimeMessage("assistant","unused",null,List.of()))){
            var json=first.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            var workflows=first.getBean(WorkflowApplicationService.class);
            var draft=json.readValue("""
                    {"name":"orphan","schemaVersion":1,"nodes":[
                    {"nodeKey":"start","name":"start","type":"START","config":{}},
                    {"nodeKey":"end","name":"end","type":"END","config":{"output":"ok"}}],
                    "edges":[{"edgeKey":"a","sourceNodeKey":"start","targetNodeKey":"end"}]}
                    """,WorkflowDraftRequest.class);
            var version=workflows.publish(workflows.create(draft));
            first.getBean(com.hify.workflow.infrastructure.WorkflowRunRepository.class).saveAndFlush(
                    new com.hify.workflow.domain.WorkflowRun(oldRun,version.id(),version.checksum(),"input",Instant.now()));
            first.getBean(com.hify.workflow.infrastructure.WorkflowNodeRunRepository.class).saveAndFlush(
                    new com.hify.workflow.domain.WorkflowNodeRun(UUID.randomUUID().toString(),oldRun,1,"start","START",Instant.now()));
            first.getBean(com.hify.workflow.application.WorkflowRecovery.class).interruptOrphanedExecutions();
            assertThat(database(url).queryForObject("select status from workflow_runs where id=?",String.class,oldRun)).isEqualTo("RUNNING");
        }
        try(var second=start(url,request->new RuntimeMessage("assistant","unused",null,List.of()))){
            assertThat(database(url).queryForObject("select status from workflow_runs where id=?",String.class,oldRun)).isEqualTo("INTERRUPTED");
            assertThat(database(url).queryForObject("select status from workflow_node_runs where workflow_run_id=?",String.class,oldRun)).isEqualTo("INTERRUPTED");
        }
    }

    @Test void interruptedWorkflowLeavesAttemptTraceAndRestartsWithFreshAttempt() throws Exception {
        String url=database(), run, version;
        var entered=new CountDownLatch(1); var interrupted=new CountDownLatch(1);
        var knowledge=mock(KnowledgeRetrievalPort.class);
        when(knowledge.search("fixture-kb","blocked",3)).thenAnswer(invocation -> {
            entered.countDown();
            try {new CountDownLatch(1).await();return List.of();}
            catch(InterruptedException stop){interrupted.countDown();throw new IllegalStateException("test read interrupted");}
        });
        try(var first=start(url,request->new RuntimeMessage("assistant","unused",null,List.of()),knowledge)){
            var workflows=first.getBean(WorkflowApplicationService.class);
            var json=first.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
            var draft=json.readValue("""
                    {"name":"shutdown-workflow","schemaVersion":1,"nodes":[
                    {"nodeKey":"start","name":"start","type":"START","config":{}},
                    {"nodeKey":"lookup","name":"lookup","type":"KNOWLEDGE","config":{"knowledgeBaseId":"fixture-kb","query":"{{start.userMessage}}"}},
                    {"nodeKey":"end","name":"end","type":"END","config":{"output":"workflow recovered"}}],
                    "edges":[{"edgeKey":"a","sourceNodeKey":"start","targetNodeKey":"lookup"},
                    {"edgeKey":"b","sourceNodeKey":"lookup","targetNodeKey":"end"}]}
                    """,WorkflowDraftRequest.class);
            String workflow=workflows.create(draft);version=workflows.publish(workflow).id();
            var agents=first.getBean(AgentService.class);
            String agent=agents.create(new AgentUpsertRequest("shutdown-agent","","workflow","mock","hify-mock",0.2,2048,6,10,List.of(),true));
            agents.replaceWorkflow(agent,new AgentWorkflowBindingRequest(workflow));agents.publish(agent);
            run=first.getBean(RunApplicationService.class).create(conversation(first,agent),"workflow-stop","blocked").run().getId();
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(interrupted.await(5,TimeUnit.SECONDS)).isTrue();
        JdbcTemplate db=database(url);
        assertThat(db.queryForObject("select state from agent_runs where id=?",String.class,run)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("select status from workflow_runs where workflow_version_id=?",String.class,version)).isEqualTo("INTERRUPTED");
        assertThat(db.queryForList("select status from workflow_node_runs where node_key='lookup'",String.class)).containsExactly("INTERRUPTED");
        assertThat(db.queryForObject("select count(*) from workflow_node_runs where node_key='end'",Integer.class)).isZero();
        assertThat(db.queryForList("select event_type from run_events where run_id=?",String.class,run)).contains("workflow.interrupted","run.interrupted").doesNotContain("run.cancelled","run.failed");
        doReturn(List.of()).when(knowledge).search("fixture-kb","blocked",3);
        try(var second=start(url,request->new RuntimeMessage("assistant","unused",null,List.of()),knowledge)){
            var service=second.getBean(RunApplicationService.class);awaitTerminal(service,run);
            assertThat(service.get(run).getState()).isEqualTo(RunState.COMPLETED);
            assertThat(db.queryForList("select status from workflow_runs where workflow_version_id=? order by created_at",String.class,version))
                    .containsExactly("INTERRUPTED","SUCCEEDED");
            assertThat(db.queryForObject("select count(*) from chat_messages where role='assistant'",Integer.class)).isEqualTo(1);
        }
    }

    @Test void explicitCancellationBeforeShutdownRemainsCancelledAndIsNotRecovered() throws Exception {
        String url=database(),run;var entered=new CountDownLatch(1);
        try(var first=start(url,request->{
            entered.countDown();
            try {new CountDownLatch(1).await();throw new AssertionError();}
            catch(InterruptedException stop){Thread.currentThread().interrupt();throw new ExecutionCancelledException("cancelled");}
        })){
            var service=first.getBean(RunApplicationService.class);
            run=service.create(conversation(first),"cancel-before-close","hello").run().getId();
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();service.cancel(run);
        }
        assertThat(database(url).queryForObject("select terminal_reason from agent_runs where id=?",String.class,run))
                .as("must commit before restarting; recovery must not hide a shutdown DB failure").isEqualTo("CANCELLED");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var second=start(url,request->{calls.incrementAndGet();return new RuntimeMessage("assistant","unexpected",null,List.of());})){
            assertThat(second.getBean(RunApplicationService.class).get(run).getState()).isEqualTo(RunState.CANCELLED);
            assertThat(calls).hasValue(0);
            assertThat(database(url).queryForObject("select count(*) from chat_messages where role='assistant'",Integer.class)).isZero();
        }
    }

    @Test void interruptedModelIsRecoverableAfterActualApplicationRestart() throws Exception {
        verifyModelShutdownRecovery(database());
    }
    static void verifyModelShutdownRecovery(String url) throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        ModelClient blocked = request -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); throw new AssertionError("unexpected unblock"); }
            catch (InterruptedException stop) {
                Thread.currentThread().interrupt(); interrupted.countDown();
                throw new ExecutionCancelledException("test model interrupted");
            }
        };
        String run;
        try (var first = start(url, blocked)) {
            var service = first.getBean(RunApplicationService.class);
            run = service.create(conversation(first), "shutdown-model", "hello").run().getId();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // Unmodified production destruction: no listener invokes pool.shutdown().
        }
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        JdbcTemplate db = database(url);
        assertThat(db.queryForObject("select state from agent_runs where id=?", String.class, run)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("select cancel_requested_at from agent_runs where id=?", Object.class, run)).isNull();
        assertThat(db.queryForList("select event_type from run_events where run_id=?", String.class, run))
                .contains("run.interrupted").doesNotContain("run.cancelled", "run.failed", "run.completed");
        try (var second = start(url, request -> new RuntimeMessage("assistant", "recovered", null, List.of()))) {
            var service = second.getBean(RunApplicationService.class);
            awaitTerminal(service, run);
            assertThat(service.get(run).getState()).isEqualTo(RunState.COMPLETED);
            assertThat(service.get(run).getOutputMessage()).isEqualTo("recovered");
            assertThat(db.queryForObject("select count(*) from chat_messages where role='assistant'", Integer.class)).isEqualTo(1);
            assertThat(db.queryForList("select event_type from run_events where run_id=?", String.class, run)).contains("checkpoint.restored");
        }
    }

    private static ConfigurableApplicationContext start(String url, ModelClient client) {
        return start(url,client,null);
    }
    private static ConfigurableApplicationContext start(String url, ModelClient client,KnowledgeRetrievalPort knowledge) {
        return start(url,client,knowledge,null,null);
    }
    private static ConfigurableApplicationContext start(String url, ModelClient client,KnowledgeRetrievalPort knowledge,
                                                        CountDownLatch computed,CountDownLatch release) {
        ModelClientFactory factory = mock(ModelClientFactory.class);
        when(factory.create(any())).thenReturn(client);
        return new SpringApplicationBuilder(HifyApplication.class).web(WebApplicationType.NONE)
                .initializers(context -> {
                    var beans=(GenericApplicationContext)context;
                    beans.registerBean("shutdownTestModelFactory",ModelClientFactory.class,()->factory,definition->definition.setPrimary(true));
                    if(knowledge!=null)beans.registerBean("shutdownTestKnowledge",KnowledgeRetrievalPort.class,()->knowledge,definition->definition.setPrimary(true));
                    if(computed!=null)beans.registerBean("shutdownTestQueryLoop",com.hify.runtime.QueryLoop.class,()->{
                        var loop=spy(new com.hify.runtime.QueryLoop(context.getBean(com.hify.runtime.ToolRuntime.class),
                                context.getBean(com.hify.runtime.context.ContextManager.class),context.getBean(com.hify.common.ExecutionLifecycle.class)));
                        doAnswer(invocation->{
                            Object result=invocation.callRealMethod();computed.countDown();
                            assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return result;
                        }).when(loop).run(org.mockito.ArgumentMatchers.anyList(),any(),any(),org.mockito.ArgumentMatchers.anyDouble(),
                                any(com.hify.runtime.CapabilitySnapshot.class),any(),any(),any());
                        return loop;
                    },definition->definition.setPrimary(true));
                })
                .run("--spring.datasource.url="+url, "--spring.datasource.username="+username(url), "--spring.datasource.password="+password(url),
                        "--hify.run-timeout=120s", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }
    private static String conversation(ConfigurableApplicationContext context) {
        return conversation(context,"demo-agent");
    }
    private static String conversation(ConfigurableApplicationContext context,String agentId) {
        var agent = context.getBean(AgentQueryService.class).requirePublished(agentId);
        String id=UUID.randomUUID().toString();
        context.getBean(ConversationRepository.class).saveAndFlush(new Conversation(id,agentId,agent.versionId(),"shutdown",Instant.now()));
        return id;
    }
    private static String database(){return "jdbc:h2:mem:shutdown-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";}
    // Only ephemeral H2 and our Testcontainers fixture are used; no environment credentials.
    private static String username(String url){return url.startsWith("jdbc:postgresql:")?"hify":"sa";}
    private static String password(String url){return url.startsWith("jdbc:postgresql:")?"hify":"";}
    private static JdbcTemplate database(String url){return new JdbcTemplate(new DriverManagerDataSource(url,username(url),password(url)));}
    private static void awaitTerminal(RunApplicationService service,String run) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!service.get(run).getState().terminal()&&System.nanoTime()<end)Thread.sleep(10);
        assertThat(service.get(run).getState().terminal()).isTrue();
    }
}
