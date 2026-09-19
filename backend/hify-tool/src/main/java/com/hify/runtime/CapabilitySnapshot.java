package com.hify.runtime;

import java.util.List;
import java.util.Set;

/** Immutable capability view pinned to one accepted Run. */
public record CapabilitySnapshot(
        String revision,
        String toolSchemaDigest,
        Set<String> enabledTools,
        List<ToolDefinition> definitions
) {
    public CapabilitySnapshot {
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("Capability revision is required");
        if (toolSchemaDigest == null || toolSchemaDigest.isBlank()) throw new IllegalArgumentException("Tool schema digest is required");
        enabledTools = Set.copyOf(enabledTools);
        definitions = List.copyOf(definitions);
    }
}
