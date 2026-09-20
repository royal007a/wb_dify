package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import java.util.List;

/** Projects long-lived memory into a model-facing view; canonical messages remain unchanged. */
public interface ContextMemoryProjection {
    ContextMemoryProjection NOOP = (runId, messages, recentTurns) ->
            new Projection(List.copyOf(messages), false, null, List.of(), 0);

    Projection project(String runId, List<RuntimeMessage> canonicalMessages, int recentTurns);

    record Projection(List<RuntimeMessage> messages, boolean layered, String summaryId,
                      List<String> catalogRefs, int retainedFromIndex) {}
}
