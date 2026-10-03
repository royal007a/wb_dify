package com.hify.api;

import com.hify.application.*;
import com.hify.domain.*;
import com.hify.infra.*;
import com.hify.runtime.*;
import com.hify.runtime.plan.*;
import com.hify.runtime.state.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:history-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="})
class HistoryReplayTest {
    @Autowired CommittedHistoryWriter writer;
    @Autowired ConversationRepository conversations;
    @Autowired AgentRunRepository runs;
    @Autowired JdbcTemplate db;
    String run;
    final List<RuntimeMessage> prefix=List.of(RuntimeMessage.user("fixture"));
    @BeforeEach void setup(){
        String conversation=UUID.randomUUID().toString();run=UUID.randomUUID().toString();
        conversations.saveAndFlush(new Conversation(conversation,"demo-agent","history",Instant.now()));
        runs.saveAndFlush(new AgentRun(run,conversation,run,"hash","fixture",Instant.now()));
    }
    @Test void replayChecksCanonicalDigestPrefixAndAppendShape(){
        var port=writer.forRun(run);var response=RuntimeMessage.assistant("original");
        port.commit("model:1",List.of(prefix.get(0),response));
        assertThat(port.replay("model:1",prefix).orElseThrow().message()).isEqualTo(response);
        assertThat(port.replay("model:2",prefix)).isEmpty();
        assertThatThrownBy(()->port.replay("model:1",List.of(RuntimeMessage.user("changed")))).isInstanceOf(HistoryReplayException.class);
        assertThatThrownBy(()->port.replay("model:1",List.of())).isInstanceOf(HistoryReplayException.class);
        db.update("update run_history_commits set messages_json='[]' where run_id=?",run);
        assertThatThrownBy(()->port.replay("model:1",prefix)).isInstanceOf(HistoryReplayException.class).hasMessageContaining("digest");
    }
    @Test void recoveryMetadataIsBoundToCanonicalDigestAndCannotBeChanged(){
        var plan=ExecutionPlan.initial("fixture");var attempt=StepAttempt.start(plan.steps().get(0),1,"tool-a","calculator").finish(false,"NONE");
        var result=ToolRuntime.ExecutionResult.success("2");var state=ExecutionContextState.empty().recordToolResult(plan,attempt,result);
        var recovery=HistoryCommitter.ToolReplay.capture(result,attempt,plan,state,1,0,0,0);
        var messages=List.of(prefix.get(0),RuntimeMessage.toolResult("tool-a","2",false));var port=writer.forRun(run);
        port.commitTool("tool:tool-a",messages,recovery);
        assertThat(port.commitTool("tool:tool-a",messages,recovery).replayed()).isTrue();
        assertThat(port.replay("tool:tool-a",prefix).orElseThrow().tool()).isEqualTo(recovery);
        var changed=HistoryCommitter.ToolReplay.capture(result,attempt,plan,state,2,0,0,0);
        assertThatThrownBy(()->port.commitTool("tool:tool-a",messages,changed)).isInstanceOf(HistoryOperationConflictException.class);
        db.update("update run_history_commits set recovery_json='{}' where run_id=?",run);
        assertThatThrownBy(()->port.replay("tool:tool-a",prefix)).isInstanceOf(HistoryReplayException.class).hasMessageContaining("digest");
    }
    @Test void legacyToolHistoryIsNotSilentlyExecutedAgain(){
        var plan=ExecutionPlan.initial("fixture");var call=new RuntimeMessage.ToolCall("a","calculator",Map.of("expression","1+1"));
        var response=RuntimeMessage.toolCalls(List.of(call));var port=writer.forRun(run);
        port.commit("model:1",List.of(prefix.get(0),response));
        port.commit("tool:a",List.of(prefix.get(0),response,RuntimeMessage.toolResult("a","2",false)));
        var runtime=spy(new ToolRuntime());var capability=runtime.snapshot("test",Set.of("calculator"));
        var checkpoint=ExecutionCheckpoint.capture(0,0,plan,ExecutionContextState.empty(),prefix);
        assertThatThrownBy(()->new QueryLoop(runtime).resume(checkpoint,r->{throw new AssertionError("model must be replayed");},"mock",0.2,capability,
                new QueryLoop.RunPolicy(3,5,16384,Duration.ofSeconds(5),()->false),QueryLoop.RunObserver.NOOP,
                new RunRuntimeIdentity(run,capability.revision(),capability.toolSchemaDigest(),(a,b)->true,port)))
                .isInstanceOf(HistoryReplayException.class).hasMessageContaining("Legacy");
        verify(runtime,never()).execute(any(),any(CapabilitySnapshot.class),any(),any());
    }
    @Test void navigationEvidenceAndOriginalIdsSurviveToolReplay(){
        var plan=ExecutionPlan.initial("fixture");var call=new RuntimeMessage.ToolCall("nav","history.search",Map.of());
        var model=RuntimeMessage.toolCalls(List.of(call));var attempt=StepAttempt.start(plan.steps().get(0),1,call.id(),call.name()).finish(false,"NONE");
        ToolEvidencePayload navigation=new ToolEvidencePayload(){
            public EvidenceKind evidenceKind(){return EvidenceKind.NAVIGATION;}public String sourceRef(){return "catalog:test";}
            public String valueDigest(){return "digest";}public String evidenceSummary(){return "navigation only";}public int estimatedTokens(){return 7;}
            public String toString(){return "candidate";}
        };
        var result=ToolRuntime.ExecutionResult.success(navigation);
        var state=ExecutionContextState.empty().recordToolResult(plan,attempt,result);
        var port=writer.forRun(run);port.commit("model:1",List.of(prefix.get(0),model));
        port.commitTool("tool:nav",List.of(prefix.get(0),model,RuntimeMessage.toolResult("nav",navigation,false)),
                HistoryCommitter.ToolReplay.capture(result,attempt,plan,state,1,1,7,12));
        var capability=new CapabilitySnapshot("revision","digest",Set.of("history.search"),List.of(new ToolDefinition("history.search","navigation",Map.of("type","object"),"read")));
        var calls=new AtomicInteger();
        var resumed=new QueryLoop(new ToolRuntime()).resume(ExecutionCheckpoint.capture(0,0,plan,ExecutionContextState.empty(),prefix),
                r->{calls.incrementAndGet();return RuntimeMessage.assistant("must not finish from navigation");},"mock",0.2,capability,
                new QueryLoop.RunPolicy(3,5,16384,Duration.ofSeconds(5),()->false),QueryLoop.RunObserver.NOOP,
                new RunRuntimeIdentity(run,capability.revision(),capability.toolSchemaDigest(),(a,b)->true,port));
        assertThat(calls).hasValue(1);
        assertThat(resumed.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(resumed.contextState().evidence()).isEqualTo(state.evidence());
        assertThat(resumed.contextState().claims()).isEqualTo(state.claims());
        assertThat(resumed.contextState().evidence().get(0).status()).isEqualTo(EvidenceItem.Status.UNVERIFIED);
    }
    @Test void replayAcrossLocalReplanKeepsOriginalStepAndDoesNotExecuteToolsAgain(){
        var runtime=spy(new ToolRuntime());var capability=runtime.snapshot("test",Set.of("calculator"));
        var plan=ExecutionPlan.initial("fixture");var checkpoint=ExecutionCheckpoint.capture(0,0,plan,ExecutionContextState.empty(),prefix);
        var port=writer.forRun(run);var identity=new RunRuntimeIdentity(run,capability.revision(),capability.toolSchemaDigest(),(a,b)->true,port);
        var policy=new QueryLoop.RunPolicy(3,5,16384,Duration.ofSeconds(5),()->false);
        var loop=new QueryLoop(runtime);var modelCalls=new AtomicInteger();
        ModelClient model=request->{
            if(modelCalls.incrementAndGet()>1)return RuntimeMessage.assistant("2");
            return RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall("wrong","math",Map.of("expression","1+1")),
                    new RuntimeMessage.ToolCall("right","calculator",Map.of("expression","1+1"))));
        };
        var observer=new QueryLoop.RunObserver(){
            public void onHistoryCommitted(String operation,HistoryCommitter.CommitReceipt receipt){
                if(operation.equals("tool:right"))throw new com.hify.common.ExecutionSuspendedException();
            }
        };
        assertThatThrownBy(()->loop.resume(checkpoint,model,"mock",0.2,capability,policy,observer,identity))
                .isInstanceOf(com.hify.common.ExecutionSuspendedException.class);
        clearInvocations(runtime);
        var result=loop.resume(checkpoint,model,"mock",0.2,capability,policy,QueryLoop.RunObserver.NOOP,identity);
        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(result.plan().version()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(2);
        assertThat(modelCalls).hasValue(2);
        verify(runtime,never()).execute(any(),any(CapabilitySnapshot.class),any(),any());
    }
}
