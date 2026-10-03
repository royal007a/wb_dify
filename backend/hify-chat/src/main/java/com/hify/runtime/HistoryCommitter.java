package com.hify.runtime;

import java.util.List;
import java.util.Optional;
import com.hify.runtime.plan.StepAttempt;
import com.hify.runtime.plan.ExecutionPlan;
import com.hify.runtime.state.ExecutionContextState;

@FunctionalInterface
public interface HistoryCommitter {
    HistoryCommitter NOOP = (operationId, messages) ->
            new CommitReceipt(0, "noop", false);

    CommitReceipt commit(String operationId, List<RuntimeMessage> messages);

    default CommitReceipt commitTool(String operationId,List<RuntimeMessage> messages,ToolReplay replay){
        return commit(operationId,messages);
    }

    /** Returns one canonical append after the exact supplied prefix, or empty if never committed. */
    default Optional<Replay> replay(String operationId,List<RuntimeMessage> prefix){return Optional.empty();}

    record Replay(RuntimeMessage message,ToolReplay tool) {}

    /** State immediately after the last attempt, before deterministic continuation decisions. */
    record ToolReplay(int version,String value,boolean error,boolean fatal,boolean permissionDenied,
                      ToolRuntime.FailureType failureType,StepAttempt attempt,ExecutionPlan plan,ExecutionContextState contextState,
                      int toolCalls,int recallCalls,int recallTokens,long recallLatencyMs) {
        public ToolReplay {
            if(version!=1 || attempt==null || plan==null || contextState==null || failureType==null || toolCalls<1
                    || recallCalls<0 || recallTokens<0 || recallLatencyMs<0)throw new HistoryReplayException("Invalid tool recovery state");
        }
        public ToolRuntime.ExecutionResult result(){return new ToolRuntime.ExecutionResult(value,error,fatal,permissionDenied,failureType);}
        public static ToolReplay capture(ToolRuntime.ExecutionResult result,StepAttempt attempt,ExecutionPlan plan,ExecutionContextState context,
                                         int calls,int recallCalls,int recallTokens,long recallLatencyMs){
            return new ToolReplay(1,String.valueOf(result.value()),result.error(),result.fatal(),result.permissionDenied(),
                    result.failureType(),attempt,plan,context,calls,recallCalls,recallTokens,recallLatencyMs);
        }
    }

    record CommitReceipt(long revision, String semanticDigest, boolean replayed) {}
}
