package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolDefinition;
import java.util.List;

/** Tool-result archiving always precedes checkpoint compaction, and every mutation is remeasured. */
public final class ContextManager {
    private final ContextTokenEstimator estimator;
    private final ToolResultArchiver archiver;
    private final CheckpointCompactor compactor;

    public ContextManager() {
        this(new ContextTokenEstimator(), new ToolResultArchiver(), new DeterministicCheckpointCompactor());
    }

    public ContextManager(ContextTokenEstimator estimator, ToolResultArchiver archiver,
                          CheckpointCompactor compactor) {
        this.estimator = estimator;
        this.archiver = archiver;
        this.compactor = compactor;
    }

    public PreparedContext prepare(List<RuntimeMessage> canonicalMessages,
                                   List<ToolDefinition> tools, ContextBudget budget) {
        int original = estimator.estimate(canonicalMessages, tools);
        if (original <= budget.inputLimit()) {
            return new PreparedContext(List.copyOf(canonicalMessages), original, original,
                    false, false, List.of());
        }

        int archiveThreshold = Math.max(64, budget.inputLimit());
        ToolResultArchiver.Result archived = archiver.archive(canonicalMessages, archiveThreshold);
        int afterArchive = estimator.estimate(archived.messages(), tools);
        if (afterArchive <= budget.inputLimit()) {
            return new PreparedContext(archived.messages(), original, afterArchive,
                    !archived.references().isEmpty(), false, archived.references());
        }

        List<RuntimeMessage> compacted = compactor.compact(archived.messages(), budget.inputLimit());
        int afterCompaction = estimator.estimate(compacted, tools);
        if (afterCompaction > budget.inputLimit()) {
            throw new ContextWindowExceededException("Context uses " + afterCompaction
                    + " tokens after compaction; input limit is " + budget.inputLimit());
        }
        return new PreparedContext(compacted, original, afterCompaction,
                !archived.references().isEmpty(), true, archived.references());
    }

    public record PreparedContext(List<RuntimeMessage> messages, int originalTokens, int preparedTokens,
                                  boolean archived, boolean compacted,
                                  List<ToolResultArchiver.ArchiveReference> archiveReferences) {
        public double tokenReductionRate() {
            return originalTokens == 0 ? 0 : 1d - ((double) preparedTokens / originalTokens);
        }
    }
}
