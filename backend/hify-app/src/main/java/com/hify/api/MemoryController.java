package com.hify.api;

import com.hify.domain.ContextSummary;
import com.hify.domain.DetailRefKind;
import com.hify.domain.HistoryDetailRef;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ContextSummaryRepository;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.memory.HistoryRecallService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/runs/{runId}/memory")
public class MemoryController {
    private final AgentRunRepository runs;
    private final ContextSummaryRepository summaries;
    private final HistoryDetailRefRepository details;
    private final HistoryRecallService recall;

    public MemoryController(AgentRunRepository runs, ContextSummaryRepository summaries,
                            HistoryDetailRefRepository details, HistoryRecallService recall) {
        this.runs = runs; this.summaries = summaries; this.details = details; this.recall = recall;
    }

    @GetMapping
    public MemoryView memory(@PathVariable String runId) {
        requireRun(runId);
        List<ContextSummaryView> summaryViews = summaries.findByRunIdOrderBySummaryVersionAsc(runId)
                .stream().map(ContextSummaryView::from).toList();
        List<DetailRefView> catalog = details.findByRunIdOrderBySourceMessageIndexAsc(runId)
                .stream().map(DetailRefView::from).toList();
        return new MemoryView(summaryViews, catalog);
    }

    @PostMapping("/search")
    public HistoryRecallService.SearchResult search(@PathVariable String runId,
                                                     @Valid @RequestBody SearchRequest request) {
        return recall.search(runId, new HistoryRecallService.SearchQuery(request.query(), request.kind(),
                request.from(), request.to(), request.entity(), request.limit() == null ? 8 : request.limit()));
    }

    @GetMapping("/details/{refId}")
    public HistoryRecallService.DetailResult detail(@PathVariable String runId,
                                                    @PathVariable String refId) {
        return recall.detail(runId, refId);
    }

    private void requireRun(String runId) {
        if (!runs.existsById(runId)) throw new IllegalArgumentException("Run not found: " + runId);
    }

    public record SearchRequest(@NotBlank String query, DetailRefKind kind, Instant from, Instant to,
                                String entity, @Min(1) @Max(20) Integer limit) {}
    public record MemoryView(List<ContextSummaryView> summaries, List<DetailRefView> catalog) {}
    public record ContextSummaryView(String id, String kind, int rangeStart, int rangeEnd,
                                     String contentJson, String digest, int version, String status) {
        static ContextSummaryView from(ContextSummary value) {
            return new ContextSummaryView(value.getId(), value.getKind().name(), value.getRangeStart(),
                    value.getRangeEnd(), value.getContentJson(), value.getDigest(),
                    value.getSummaryVersion(), value.getStatus().name());
        }
    }
    public record DetailRefView(String id, int messageIndex, String kind, String role,
                                String preview, int tokenCount, Instant occurredAt,
                                long sourceRevision, String digest) {
        static DetailRefView from(HistoryDetailRef value) {
            return new DetailRefView(value.getId(), value.getSourceMessageIndex(),
                    value.getKind().name(), value.getRole(), value.getContentPreview(),
                    value.getTokenCount(), value.getOccurredAt(), value.getSourceRevision(),
                    value.getContentDigest());
        }
    }
}
