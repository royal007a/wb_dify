package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

/** Tool-result archiving always precedes checkpoint compaction, and every mutation is remeasured. */
@Component
public final class ContextManager {
    private final ContextTokenEstimator estimator;
    private final ToolResultArchiver archiver;
    private final CheckpointCompactor compactor;
    private final ContextMemoryProjection memoryProjection;
    private final int recentTurns;

    public ContextManager() {
        this(new ContextTokenEstimator(), new ToolResultArchiver(), new DeterministicCheckpointCompactor(),
                ContextMemoryProjection.NOOP, 3);
    }

    @Autowired
    public ContextManager(ContextMemoryProjection memoryProjection,
                          @Value("${hify.context.recent-turns:3}") int recentTurns) {
        this(new ContextTokenEstimator(), new ToolResultArchiver(), new DeterministicCheckpointCompactor(),
                memoryProjection, recentTurns);
    }

    public ContextManager(ContextTokenEstimator estimator, ToolResultArchiver archiver,
                          CheckpointCompactor compactor) {
        this(estimator, archiver, compactor, ContextMemoryProjection.NOOP, 3);
    }

    public ContextManager(ContextTokenEstimator estimator, ToolResultArchiver archiver,
                          CheckpointCompactor compactor, ContextMemoryProjection memoryProjection) {
        this(estimator, archiver, compactor, memoryProjection, 3);
    }

    public ContextManager(ContextTokenEstimator estimator, ToolResultArchiver archiver,
                          CheckpointCompactor compactor, ContextMemoryProjection memoryProjection,
                          int recentTurns) {
        this.estimator = estimator;
        this.archiver = archiver;
        this.compactor = compactor;
        this.memoryProjection = memoryProjection;
        if (recentTurns < 1) throw new IllegalArgumentException("recentTurns must be positive");
        this.recentTurns = recentTurns;
    }

    public PreparedContext prepare(List<RuntimeMessage> canonicalMessages,
                                   List<ToolDefinition> tools, ContextBudget budget) {
        return prepare("local", canonicalMessages, tools, budget);
    }

    public PreparedContext prepare(String runId, List<RuntimeMessage> canonicalMessages,
                                   List<ToolDefinition> tools, ContextBudget budget) {
        ContextMemoryProjection.Projection projection = memoryProjection.project(
                runId, canonicalMessages, recentTurns);
        List<RuntimeMessage> modelView = projection.messages();
        int original = estimator.estimate(modelView, tools);
        if (original <= budget.inputLimit()) {
            return new PreparedContext(List.copyOf(modelView), original, original,
                    false, false, List.of(), projection.layered(), projection.summaryId(),
                    projection.catalogRefs(), projection.retainedFromIndex());
        }

        int archiveThreshold = Math.max(64, budget.inputLimit());
        ToolResultArchiver.Result archived = archiver.archive(modelView, archiveThreshold);
        int afterArchive = estimator.estimate(archived.messages(), tools);
        if (afterArchive <= budget.inputLimit()) {
            return new PreparedContext(archived.messages(), original, afterArchive,
                    !archived.references().isEmpty(), false, archived.references(),
                    projection.layered(), projection.summaryId(), projection.catalogRefs(),
                    projection.retainedFromIndex());
        }

        List<RuntimeMessage> compacted = compactor.compact(archived.messages(), budget.inputLimit());
        int afterCompaction = estimator.estimate(compacted, tools);
        if (afterCompaction > budget.inputLimit()) {
            throw new ContextWindowExceededException("Context uses " + afterCompaction
                    + " tokens after compaction; input limit is " + budget.inputLimit());
        }
        return new PreparedContext(compacted, original, afterCompaction,
                !archived.references().isEmpty(), true, archived.references(),
                projection.layered(), projection.summaryId(), projection.catalogRefs(),
                projection.retainedFromIndex());
    }

    public record PreparedContext(List<RuntimeMessage> messages, int originalTokens, int preparedTokens,
                                  boolean archived, boolean compacted,
                                  List<ToolResultArchiver.ArchiveReference> archiveReferences,
                                  boolean layeredMemory, String summaryId,
                                  List<String> catalogRefs, int retainedFromIndex) {
        public double tokenReductionRate() {
            return originalTokens == 0 ? 0 : 1d - ((double) preparedTokens / originalTokens);
        }
    }
}
