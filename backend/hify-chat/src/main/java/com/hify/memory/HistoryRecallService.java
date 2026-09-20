package com.hify.memory;

import com.hify.domain.AgentRun;
import com.hify.domain.DetailRefKind;
import com.hify.domain.HistoryDetailRef;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.ToolEvidencePayload;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HistoryRecallService {
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private final AgentRunRepository runs;
    private final HistoryDetailRefRepository details;
    private final CanonicalDetailReader reader;
    private final DataSource dataSource;

    public HistoryRecallService(AgentRunRepository runs, HistoryDetailRefRepository details,
                                CanonicalDetailReader reader, DataSource dataSource) {
        this.runs = runs; this.details = details; this.reader = reader; this.dataSource = dataSource;
    }

    /** P0 lexical/time/entity retrieval. Scores are deterministic and independent of an embedding service. */
    public SearchResult search(String runId, SearchQuery request) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        SearchQuery query = request.normalized();
        Set<String> terms = terms(query.query());
        List<ScoredDetail> ranked = new ArrayList<>();
        boolean postgresSearch = !query.query().isBlank() && isPostgres();
        List<HistoryDetailRef> candidates = postgresSearch
                ? details.searchPostgres(run.getConversationId(), query.query(), Math.max(100, query.limit() * 10))
                : details.findByConversationIdOrderByOccurredAtAsc(run.getConversationId());
        for (HistoryDetailRef ref : candidates) {
            if (query.kind() != null && ref.getKind() != query.kind()) continue;
            if (query.from() != null && ref.getOccurredAt().isBefore(query.from())) continue;
            if (query.to() != null && ref.getOccurredAt().isAfter(query.to())) continue;
            if (query.entity() != null && !contains(ref.getEntitiesText(), query.entity())) continue;
            double score = score(ref, query.query(), terms);
            if (score > 0 || query.query().isBlank()) ranked.add(ScoredDetail.from(ref, score));
        }
        ranked.sort(Comparator.comparingDouble(ScoredDetail::score).reversed()
                .thenComparing(ScoredDetail::occurredAt, Comparator.reverseOrder())
                .thenComparing(ScoredDetail::refId));
        List<ScoredDetail> matches = ranked.stream().limit(query.limit()).toList();
        String digest = MemoryDigests.sha256(query + "|" + matches.stream()
                .map(value -> value.refId() + ":" + value.score()).toList());
        return new SearchResult("history-search:" + digest.substring(0, 24), digest,
                postgresSearch ? "POSTGRES_FTS_KEYWORD" : "PORTABLE_KEYWORD",
                query, matches, Math.max(1, matches.toString().length() / 4));
    }

    /** The only recall operation that can produce VERIFIED historical evidence. */
    public DetailResult detail(String runId, String refId) {
        CanonicalDetailReader.DetailContent content = reader.read(refId);
        if (!content.ref().getRunId().equals(runId)) {
            AgentRun caller = runs.findById(runId)
                    .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
            if (!content.ref().getConversationId().equals(caller.getConversationId())) {
                throw new IllegalArgumentException("History detail is outside this conversation");
            }
        }
        RuntimeMessage message = content.message();
        return new DetailResult(content.ref().getId(), content.ref().getContentDigest(),
                content.ref().getSourceRevision(), content.ref().getSourceMessageIndex(),
                content.ref().getKind(), message.role(), message.content(), message.toolCallId(),
                message.toolCalls(), content.ref().getTokenCount());
    }

    private double score(HistoryDetailRef ref, String phrase, Set<String> terms) {
        String text = lower(ref.getSearchText());
        String keywords = lower(ref.getKeywordsText());
        String entities = lower(ref.getEntitiesText());
        double score = !phrase.isBlank() && text.contains(lower(phrase)) ? 12 : 0;
        for (String term : terms) {
            if (text.contains(term)) score += 3;
            if (keywords.contains(term)) score += 2;
            if (entities.contains(term)) score += 4;
        }
        return score;
    }

    private Set<String> terms(String value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(lower(value));
        while (matcher.find()) values.add(matcher.group());
        return values;
    }
    private boolean contains(String value, String part) { return lower(value).contains(lower(part)); }
    private String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private boolean isPostgres() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                    .contains("postgresql");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not determine history search database", exception);
        }
    }

    public record SearchQuery(String query, DetailRefKind kind, Instant from, Instant to,
                              String entity, int limit) {
        public SearchQuery normalized() {
            if (from != null && to != null && from.isAfter(to)) {
                throw new IllegalArgumentException("from must not be after to");
            }
            return new SearchQuery(query == null ? "" : query.trim(), kind, from, to,
                    entity == null || entity.isBlank() ? null : entity.trim(),
                    Math.max(1, Math.min(limit <= 0 ? 8 : limit, 20)));
        }
    }

    public record ScoredDetail(String refId, DetailRefKind kind, String role, String preview,
                               Instant occurredAt, int tokenCount, double score) {
        static ScoredDetail from(HistoryDetailRef ref, double score) {
            return new ScoredDetail(ref.getId(), ref.getKind(), ref.getRole(), ref.getContentPreview(),
                    ref.getOccurredAt(), ref.getTokenCount(), score);
        }
    }

    public record SearchResult(String sourceRef, String valueDigest, String strategy, SearchQuery query,
                               List<ScoredDetail> matches, int estimatedTokens)
            implements ToolEvidencePayload {
        @Override public EvidenceKind evidenceKind() { return EvidenceKind.NAVIGATION; }
        @Override public String evidenceSummary() { return "history search candidates=" + matches.size(); }
    }

    public record DetailResult(String sourceRef, String valueDigest, long sourceRevision,
                               int sourceMessageIndex, DetailRefKind kind, String role, String content,
                               String toolCallId, List<RuntimeMessage.ToolCall> toolCalls,
                               int estimatedTokens) implements ToolEvidencePayload {
        @Override public EvidenceKind evidenceKind() { return EvidenceKind.CANONICAL; }
        @Override public String evidenceSummary() {
            return "canonical history detail revision=" + sourceRevision + ";index=" + sourceMessageIndex;
        }
    }
}
