package com.hify.api;

import com.hify.HifyApplication;
import com.hify.agent.api.*;
import com.hify.application.RunApplicationService;
import com.hify.common.ExecutionCancelledException;
import com.hify.domain.Conversation;
import com.hify.domain.RunState;
import com.hify.infra.ConversationRepository;
import com.hify.runtime.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Nondeterministic model plus real context destruction, same isolated database on restart. */
@org.junit.jupiter.api.Timeout(60)
class HistoryRecoveryIntegrationTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void committedModelAndEarlierToolAreReplayedAfterRestart(boolean commitFirstTool) throws Exception {
        verifyRecovery("jdbc:h2:mem:history-recovery-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",commitFirstTool);
    }

    static void verifyRecovery(String url,boolean commitFirstTool) throws Exception {
        var modelCalls=new AtomicInteger();var firstToolCalls=new AtomicInteger();var secondToolCalls=new AtomicInteger();
        var firstProcess=new AtomicBoolean(true);var blocked=new CountDownLatch(1);
        ModelClient model=request->{
            int n=modelCalls.incrementAndGet();
            if(request.messages().stream().anyMatch(m->"tool".equals(m.role())))return RuntimeMessage.assistant("recovered answer");
            var calls=new ArrayList<RuntimeMessage.ToolCall>();
            if(commitFirstTool)calls.add(new RuntimeMessage.ToolCall("first-"+n,"recovery_read",Map.of("part","first")));
            calls.add(new RuntimeMessage.ToolCall("second-"+n,"recovery_read",Map.of("part","second")));
            return new RuntimeMessage("assistant","planning-"+n,null,calls);
        };
        RuntimeToolExtension tool=new RuntimeToolExtension(){
            public List<ToolDefinition> definitions(){return List.of(new ToolDefinition("recovery_read","read-only recovery fixture",
                    Map.of("type","object","properties",Map.of("part",Map.of("type","string")),"required",List.of("part")),"read"));}
            public ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call,ToolExecutionLease lease,com.hify.common.ExecutionControl control){
                if("first".equals(call.arguments().get("part")))return ToolRuntime.ExecutionResult.success("value-"+firstToolCalls.incrementAndGet());
                secondToolCalls.incrementAndGet();
                if(firstProcess.get()){
                    blocked.countDown();
                    try{new CountDownLatch(1).await();throw new AssertionError();}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new ExecutionCancelledException("fixture stopped");}
                }
                return ToolRuntime.ExecutionResult.success("second value");
            }
        };
        String run;
        JdbcTemplate db=db(url);
        try(var context=start(url,model,tool)){
            var agents=context.getBean(AgentService.class);
            String agent=agents.create(new AgentUpsertRequest("history-recovery","","recover","mock","hify-mock",0.2,2048,6,10,List.of("recovery_read"),true));
            agents.publish(agent);
            String version=context.getBean(AgentQueryService.class).requirePublished(agent).versionId();
            String conversation=UUID.randomUUID().toString();
            context.getBean(ConversationRepository.class).saveAndFlush(new Conversation(conversation,agent,version,"history",Instant.now()));
            run=context.getBean(RunApplicationService.class).create(conversation,"recovery","input").run().getId();
            assertThat(blocked.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(db.queryForObject("select count(*) from run_history_commits where run_id=? and operation_id='model:1'",Integer.class,run)).isEqualTo(1);
            if(commitFirstTool)assertThat(db.queryForObject("select count(*) from run_history_commits where run_id=? and operation_id='tool:first-1'",Integer.class,run)).isEqualTo(1);
        }
        assertThat(db.queryForObject("select state from agent_runs where id=?",String.class,run)).isEqualTo("RUNNING");
        assertThat(db.queryForList("select event_type from run_events where run_id=?",String.class,run)).contains("run.interrupted");
        firstProcess.set(false);
        try(var context=start(url,model,tool)){
            var service=context.getBean(RunApplicationService.class);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!service.get(run).getState().terminal()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(service.get(run).getState()).as("reason=%s",service.get(run).getTerminalReason()).isEqualTo(RunState.COMPLETED);
            assertThat(modelCalls).hasValue(2); // One original planning response; one final answer.
            assertThat(firstToolCalls).hasValue(commitFirstTool?1:0);
            assertThat(secondToolCalls).hasValue(2); // Interrupted read had no committed result; safe READ re-execution.
            assertThat(db.queryForObject("select count(*) from run_events where run_id=? and event_type='model.started'",Integer.class,run)).isEqualTo(2);
            assertThat(db.queryForObject("select count(*) from run_events where run_id=? and event_type='message.delta'",Integer.class,run)).isEqualTo(2);
            assertThat(db.queryForObject("select count(*) from chat_messages where role='assistant'",Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from run_history_commits where run_id=?",Integer.class,run)).isEqualTo(commitFirstTool?4:3);
        }
    }
    static ConfigurableApplicationContext start(String url,ModelClient model,RuntimeToolExtension tool){
        ModelClientFactory factory=mock(ModelClientFactory.class);when(factory.create(any())).thenReturn(model);
        return new SpringApplicationBuilder(HifyApplication.class).web(WebApplicationType.NONE).initializers(context->{
            var beans=(GenericApplicationContext)context;
            beans.registerBean("recoveryTestModelFactory",ModelClientFactory.class,()->factory,d->d.setPrimary(true));
            beans.registerBean("recoveryTestTool",RuntimeToolExtension.class,()->tool);
        }).run("--spring.datasource.url="+url,"--spring.datasource.username="+(url.startsWith("jdbc:postgresql:")?"hify":"sa"),
                "--spring.datasource.password="+(url.startsWith("jdbc:postgresql:")?"hify":""),"--hify.run-timeout=120s",
                "--spring.main.banner-mode=off","--logging.level.root=WARN");
    }
    static JdbcTemplate db(String url){return new JdbcTemplate(new DriverManagerDataSource(url,url.startsWith("jdbc:postgresql:")?"hify":"sa",url.startsWith("jdbc:postgresql:")?"hify":""));}
}
