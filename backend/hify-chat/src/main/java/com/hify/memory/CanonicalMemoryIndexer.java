package com.hify.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.AgentRun;
import com.hify.domain.DetailRefKind;
import com.hify.domain.HistoryDetailRef;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.runtime.RuntimeMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CanonicalMemoryIndexer {
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private final HistoryDetailRefRepository details;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public CanonicalMemoryIndexer(HistoryDetailRefRepository details, AgentRunRepository runs,
                                  ObjectMapper objectMapper, JdbcTemplate jdbc, DataSource dataSource) {
        this.details = details; this.runs = runs; this.objectMapper = objectMapper;
        this.jdbc = jdbc; this.dataSource = dataSource;
    }

    @Transactional
    public List<HistoryDetailRef> index(String runId, long revision,
                                        List<RuntimeMessage> messages, Instant occurredAt) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found for memory index: " + runId));
        List<HistoryDetailRef> indexed = new ArrayList<>();
        List<HistoryDetailRef> created = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            RuntimeMessage message = messages.get(index);
            String canonical = write(message);
            String digest = MemoryDigests.sha256(canonical);
            HistoryDetailRef existing = details.findByRunIdAndSourceMessageIndexAndContentDigest(
                    runId, index, digest).orElse(null);
            if (existing != null) { indexed.add(existing); continue; }
            String searchText = searchText(message);
            Set<String> keywords = terms(searchText);
            Set<String> entities = entities(message, keywords);
            String id = "det_" + MemoryDigests.sha256(runId + "|" + index + "|" + digest).substring(0, 32);
            HistoryDetailRef ref = new HistoryDetailRef(id, runId, run.getConversationId(), revision,
                    index, kind(message), message.role(), digest, truncate(searchText, 2000), searchText,
                    String.join(" ", keywords), String.join(" ", entities),
                    Math.max(1, (canonical.length() + 3) / 4), occurredAt, Instant.now());
            HistoryDetailRef saved = details.save(ref);
            indexed.add(saved); created.add(saved);
        }
        if (!created.isEmpty() && isPostgres()) {
            details.flush();
            for (HistoryDetailRef ref : created) {
                jdbc.update("UPDATE history_detail_refs SET embedding = CAST(? AS vector) WHERE id = ?",
                        LocalHistoryEmbedding.postgresLiteral(LocalHistoryEmbedding.embed(ref.getSearchText())),
                        ref.getId());
            }
        }
        return List.copyOf(indexed);
    }

    private DetailRefKind kind(RuntimeMessage message) {
        if ("tool".equals(message.role())) return DetailRefKind.TOOL_RESULT;
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) return DetailRefKind.TOOL_CALL;
        return switch (message.role()) {
            case "system" -> DetailRefKind.SYSTEM;
            case "user" -> DetailRefKind.USER_MESSAGE;
            default -> DetailRefKind.MODEL_OUTPUT;
        };
    }

    private String searchText(RuntimeMessage message) {
        StringBuilder value = new StringBuilder(message.content() == null ? "" : message.content());
        if (message.toolCalls() != null) for (RuntimeMessage.ToolCall call : message.toolCalls()) {
            value.append(' ').append(call.name()).append(' ').append(call.arguments());
        }
        return value.toString().trim();
    }

    private Set<String> terms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = TERM.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 100) result.add(matcher.group());
        return result;
    }

    private Set<String> entities(RuntimeMessage message, Set<String> keywords) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (message.toolCalls() != null) for (RuntimeMessage.ToolCall call : message.toolCalls()) {
            result.add(call.name().toLowerCase(Locale.ROOT));
            call.arguments().values().forEach(value -> result.add(String.valueOf(value).toLowerCase(Locale.ROOT)));
        }
        keywords.stream().filter(value -> value.matches(".*[0-9_].*") || value.length() > 5)
                .limit(30).forEach(result::add);
        return result;
    }

    private String write(RuntimeMessage message) {
        try { return objectMapper.writeValueAsString(message); }
        catch (Exception exception) { throw new IllegalStateException("Could not index history message", exception); }
    }
    private String truncate(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private boolean isPostgres() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT)
                    .contains("postgresql");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not determine memory index database", exception);
        }
    }
}
