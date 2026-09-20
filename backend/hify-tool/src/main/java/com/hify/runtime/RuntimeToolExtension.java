package com.hify.runtime;

import com.hify.common.ExecutionControl;
import java.util.List;

/** Spring-discovered tool implementation. Definitions are included in the capability snapshot. */
public interface RuntimeToolExtension {
    List<ToolDefinition> definitions();
    ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call, ToolExecutionLease lease,
                                        ExecutionControl control);
}
