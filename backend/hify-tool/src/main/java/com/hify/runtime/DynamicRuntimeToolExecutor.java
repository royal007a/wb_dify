package com.hify.runtime;

import com.hify.common.ExecutionControl;

/** Executes versioned tool definitions supplied by an immutable Agent capability snapshot. */
public interface DynamicRuntimeToolExecutor {
    boolean supports(ToolDefinition definition);
    ToolRuntime.ExecutionResult execute(RuntimeMessage.ToolCall call, ToolDefinition definition,
                                        ToolExecutionLease lease, ExecutionControl control);
}
