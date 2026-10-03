package com.hify.runtime;

import com.hify.common.ExecutionControl;
import com.hify.runtime.state.ExecutionContextState;

/** Application-specific evidence contract. It does not own retrieval or the execution loop. */
public interface CompletionVerifier {
    CompletionVerifier NONE=new CompletionVerifier() {};
    default ExecutionContextState initialState(){return ExecutionContextState.empty();}
    default ExecutionContextState verify(String answer,ExecutionContextState state,int planVersion,ExecutionControl control){return state;}
    default boolean withholdUnverifiedOutput(){return false;}
}
