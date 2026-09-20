package com.hify.memory;

import com.hify.domain.ContextSummary;
import com.hify.domain.ContextSummaryKind;
import com.hify.domain.HistoryDetailRef;
import com.hify.infra.ContextSummaryRepository;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.context.ContextMemoryProjection;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class LayeredContextMemoryService implements ContextMemoryProjection {
    private final HistoryDetailRefRepository details;
    private final ContextSummaryRepository summaries;
    private final StructuredSummaryService summaryService;

    public LayeredContextMemoryService(HistoryDetailRefRepository details,
                                       ContextSummaryRepository summaries,
                                       StructuredSummaryService summaryService) {
        this.details = details; this.summaries = summaries; this.summaryService = summaryService;
    }

    @Override
    public Projection project(String runId, List<RuntimeMessage> canonicalMessages, int recentTurns) {
        if (runId == null || runId.isBlank() || "local".equals(runId) || canonicalMessages.isEmpty()) {
            return ContextMemoryProjection.NOOP.project(runId, canonicalMessages, recentTurns);
        }
        int cutoff = recentTurnCutoff(canonicalMessages, Math.max(1, recentTurns));
        if (cutoff <= 1) return ContextMemoryProjection.NOOP.project(runId, canonicalMessages, recentTurns);

        int rangeEnd = cutoff - 1;
        List<HistoryDetailRef> oldRefs = details.findByRunIdOrderBySourceMessageIndexAsc(runId).stream()
                .filter(ref -> ref.getSourceMessageIndex() <= rangeEnd).toList();
        if (oldRefs.isEmpty()) return ContextMemoryProjection.NOOP.project(runId, canonicalMessages, recentTurns);

        ContextSummary summary = summaries.findTopByRunIdAndKindOrderBySummaryVersionDesc(
                        runId, ContextSummaryKind.CHECKPOINT)
                .filter(value -> value.getRangeEnd() == oldRefs.get(oldRefs.size() - 1).getSourceMessageIndex())
                .orElseGet(() -> summaryService.createCheckpoint(
                        runId, oldRefs.get(oldRefs.size() - 1).getSourceMessageIndex()));

        List<String> catalogRefs = oldRefs.stream().map(HistoryDetailRef::getId).toList();
        StringBuilder catalog = new StringBuilder();
        for (HistoryDetailRef ref : oldRefs) {
            catalog.append(ref.getId()).append(" | ").append(ref.getKind()).append(" | ")
                    .append(ref.getOccurredAt()).append(" | ").append(ref.getContentPreview()).append('\n');
        }
        RuntimeMessage memory = RuntimeMessage.system("""
                [HIFY_CONTEXT_MEMORY]
                summaryStatus=%s; summaryId=%s
                summary=%s
                detailCatalog:
                %s
                This summary and catalog are navigation only. Call history.search then history.detail before using an old detail as evidence.
                """.formatted(summary.getStatus(), summary.getId(), summary.getContentJson(), catalog));

        List<RuntimeMessage> projected = new ArrayList<>();
        int firstNonSystem = 0;
        while (firstNonSystem < canonicalMessages.size()
                && "system".equals(canonicalMessages.get(firstNonSystem).role())) {
            projected.add(canonicalMessages.get(firstNonSystem++));
        }
        projected.add(memory);
        projected.addAll(canonicalMessages.subList(Math.max(cutoff, firstNonSystem), canonicalMessages.size()));
        return new Projection(List.copyOf(projected), true, summary.getId(), catalogRefs, cutoff);
    }

    private int recentTurnCutoff(List<RuntimeMessage> messages, int recentTurns) {
        int users = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role()) && ++users == recentTurns) return index;
        }
        return 0;
    }
}
