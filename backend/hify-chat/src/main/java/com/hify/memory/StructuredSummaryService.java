package com.hify.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.ContextSummary;
import com.hify.domain.ContextSummaryClaim;
import com.hify.domain.ContextSummaryKind;
import com.hify.domain.ContextSummaryStatus;
import com.hify.domain.HistoryDetailRef;
import com.hify.domain.SummaryClaimStatus;
import com.hify.infra.ContextSummaryClaimRepository;
import com.hify.infra.ContextSummaryRepository;
import com.hify.infra.HistoryDetailRefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class StructuredSummaryService {
    private static final List<String> CONSTRAINTS = List.of("must", "never", "required", "必须", "不得", "禁止", "约束");
    private static final List<String> DECISIONS = List.of("decide", "choose", "selected", "决定", "选择", "采用");
    private final HistoryDetailRefRepository details;
    private final ContextSummaryRepository summaries;
    private final ContextSummaryClaimRepository claims;
    private final CanonicalDetailReader reader;
    private final ObjectMapper objectMapper;

    public StructuredSummaryService(HistoryDetailRefRepository details, ContextSummaryRepository summaries,
                                    ContextSummaryClaimRepository claims, CanonicalDetailReader reader,
                                    ObjectMapper objectMapper) {
        this.details = details; this.summaries = summaries; this.claims = claims;
        this.reader = reader; this.objectMapper = objectMapper;
    }

    @Transactional
    public ContextSummary createCheckpoint(String runId, int rangeEnd) {
        List<HistoryDetailRef> refs = details.findByRunIdOrderBySourceMessageIndexAsc(runId).stream()
                .filter(ref -> ref.getSourceMessageIndex() <= rangeEnd).toList();
        if (refs.isEmpty()) throw new IllegalArgumentException("No indexed history is available for summary");
        List<Map<String, String>> facts = new ArrayList<>();
        List<Map<String, String>> constraints = new ArrayList<>();
        List<Map<String, String>> decisions = new ArrayList<>();
        List<Map<String, String>> gaps = new ArrayList<>();
        String goal = "";
        for (HistoryDetailRef ref : refs) {
            String value = reader.read(ref.getId()).message().content();
            if (value == null || value.isBlank()) value = ref.getSearchText();
            Map<String, String> item = Map.of("statement", truncate(value, 500), "sourceRef", ref.getId());
            facts.add(item);
            String normalized = value.toLowerCase(Locale.ROOT);
            if (contains(normalized, CONSTRAINTS)) constraints.add(item);
            if (contains(normalized, DECISIONS)) decisions.add(item);
            if (value.contains("?") || value.contains("？") || value.contains("缺少")) gaps.add(item);
            if ("user".equals(ref.getRole())) goal = truncate(value, 500);
        }
        List<String> sourceRefs = refs.stream().map(HistoryDetailRef::getId).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", "hify.model-readable-summary.v1");
        content.put("goal", goal); content.put("facts", facts); content.put("constraints", constraints);
        content.put("decisions", decisions); content.put("openGaps", gaps); content.put("sourceRefs", sourceRefs);
        String contentJson = write(content);
        int version = summaries.findTopByRunIdAndKindOrderBySummaryVersionDesc(runId, ContextSummaryKind.CHECKPOINT)
                .map(value -> value.getSummaryVersion() + 1).orElse(1);
        Instant now = Instant.now();
        ContextSummary summary = summaries.save(new ContextSummary(UUID.randomUUID().toString(), runId,
                refs.get(0).getConversationId(), ContextSummaryKind.CHECKPOINT,
                refs.get(0).getSourceMessageIndex(), refs.get(refs.size() - 1).getSourceMessageIndex(),
                contentJson, write(sourceRefs), write(facts), write(constraints), write(decisions),
                write(gaps), MemoryDigests.sha256(contentJson + write(sourceRefs)), version, now));
        for (Map<String, String> fact : facts) {
            appendClaimInternal(summary, "detail:" + fact.get("sourceRef"), "FACT",
                    fact.get("statement"), List.of(fact.get("sourceRef")), now);
        }
        validate(summary.getId());
        return summaries.findById(summary.getId()).orElseThrow();
    }

    @Transactional
    public ContextSummaryClaim appendClaim(String summaryId, String claimKey, String claimType,
                                           String statement, List<String> sourceRefs) {
        ContextSummary summary = summaries.findById(summaryId)
                .orElseThrow(() -> new IllegalArgumentException("Summary not found"));
        ContextSummaryClaim claim = appendClaimInternal(summary, claimKey, claimType, statement,
                sourceRefs, Instant.now());
        reconcile(summary, claimKey);
        validate(summaryId);
        return claims.findById(claim.getId()).orElseThrow();
    }

    @Transactional
    public ValidationResult validate(String summaryId) {
        ContextSummary summary = summaries.findById(summaryId)
                .orElseThrow(() -> new IllegalArgumentException("Summary not found"));
        int missing = 0;
        for (ContextSummaryClaim claim : claims.findBySummaryId(summaryId)) {
            List<String> refs = readRefs(claim.getSourceRefsJson());
            boolean valid = !refs.isEmpty();
            for (String ref : refs) {
                try { reader.read(ref); } catch (RuntimeException exception) { valid = false; }
            }
            if (!valid) { claim.missingSource(); claims.save(claim); missing++; }
        }
        boolean conflict = claims.findBySummaryId(summaryId).stream()
                .anyMatch(claim -> claim.getStatus() == SummaryClaimStatus.CONTRADICTED);
        summary.markStatus(missing > 0 ? ContextSummaryStatus.INVALID
                : conflict ? ContextSummaryStatus.CONFLICTING : ContextSummaryStatus.CURRENT);
        summaries.save(summary);
        return new ValidationResult(summary.getStatus(), missing, conflict);
    }

    private ContextSummaryClaim appendClaimInternal(ContextSummary summary, String key, String type,
                                                    String statement, List<String> refs, Instant now) {
        SummaryClaimStatus status = refs == null || refs.isEmpty()
                ? SummaryClaimStatus.MISSING_SOURCE : SummaryClaimStatus.VERIFIED;
        ContextSummaryClaim claim = new ContextSummaryClaim(UUID.randomUUID().toString(), summary.getId(),
                key, type, statement, write(refs == null ? List.of() : refs), status, now);
        return claims.save(claim);
    }

    private void reconcile(ContextSummary summary, String key) {
        List<ContextSummaryClaim> group = claims.findByClaimKey(key).stream()
                .filter(claim -> claim.getSummaryId().equals(summary.getId())).toList();
        long variants = group.stream().map(ContextSummaryClaim::getStatement).distinct().count();
        if (variants > 1) {
            String conflictGroup = "conflict:" + key;
            group.forEach(claim -> { claim.contradict(conflictGroup); claims.save(claim); });
            summary.markStatus(ContextSummaryStatus.CONFLICTING);
            summaries.save(summary);
        }
    }

    private boolean contains(String value, List<String> markers) { return markers.stream().anyMatch(value::contains); }
    private String truncate(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Could not write summary JSON", exception); }
    }
    private List<String> readRefs(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Could not read summary refs", exception); }
    }
    public record ValidationResult(ContextSummaryStatus status, int missingSources, boolean conflicts) {}
}
