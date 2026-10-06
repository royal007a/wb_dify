package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.List;

@FunctionalInterface
public interface CheckpointCompactor {
    /** Advisory target only: implementations must preserve policy and complete exchanges.
     * Returning an oversized view is permitted; ContextManager owns final admission. */
    List<RuntimeMessage> compact(List<RuntimeMessage> messages, int targetTokens);
}
