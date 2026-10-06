package com.hify.runtime.context;

import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;

/** Initial large-result archiving, policy-preserving turn compaction, then older-result archiving. */
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

        // Legacy first-pass character heuristic, not a characters-to-tokens conversion.
        // Admission and cumulative pressure are always measured by the estimator below.
        int archiveThresholdChars = Math.max(64, budget.inputLimit());
        ToolResultArchiver.Result archived = archiver.archive(modelView, archiveThresholdChars);
        int afterArchive = estimator.estimate(archived.messages(), tools);
        if (afterArchive <= budget.inputLimit()) {
            return new PreparedContext(archived.messages(), original, afterArchive,
                    !archived.references().isEmpty(), false, archived.references(),
                    projection.layered(), projection.summaryId(), projection.catalogRefs(),
                    projection.retainedFromIndex());
        }

        List<RuntimeMessage> compacted = compactor.compact(archived.messages(), budget.inputLimit());
        int afterCompaction = estimator.estimate(compacted, tools);
        // Many individually small results can jointly overflow one user turn.
        // Preserve all calls/results and the newest batch; only replace older
        // result bodies with canonical-history pointers, oldest first.
        var references = new ArrayList<>(archived.references());
        if (afterCompaction > budget.inputLimit()) {
            var rescue = archiveEarlierResults(compacted, tools, budget.inputLimit());
            compacted = rescue.messages();
            references.addAll(rescue.references());
            afterCompaction = estimator.estimate(compacted, tools);
        }
        if (afterCompaction > budget.inputLimit()) {
            throw new ContextWindowExceededException("Context uses " + afterCompaction
                    + " tokens after compaction; input limit is " + budget.inputLimit());
        }
        return new PreparedContext(compacted, original, afterCompaction,
                !references.isEmpty(), true, List.copyOf(references),
                projection.layered(), projection.summaryId(), projection.catalogRefs(),
                projection.retainedFromIndex());
    }

    private ToolResultArchiver.Result archiveEarlierResults(List<RuntimeMessage> messages,
                                                            List<ToolDefinition> tools, int inputLimit) {
        int latestBatch = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            RuntimeMessage message = messages.get(index);
            if ("assistant".equals(message.role()) && message.toolCalls() != null
                    && !message.toolCalls().isEmpty()) {
                latestBatch = index;
                break;
            }
        }
        var projected = new ArrayList<>(messages);
        var references = new ArrayList<ToolResultArchiver.ArchiveReference>();
        int measured = estimator.estimate(projected, tools);
        for (int index = 0; index < latestBatch && measured > inputLimit; index++) {
            RuntimeMessage original = projected.get(index);
            if (!"tool".equals(original.role()) || original.toolCallId() == null) continue;
            var candidate = archiver.archive(List.of(original), 0);
            if (candidate.references().isEmpty()) continue;
            projected.set(index, candidate.messages().get(0));
            int next = estimator.estimate(projected, tools);
            if (next < measured) {
                references.addAll(candidate.references());
                measured = next;
            } else {
                projected.set(index, original);
            }
        }
        return new ToolResultArchiver.Result(List.copyOf(projected), List.copyOf(references));
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
