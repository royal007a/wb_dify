package com.hify.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.HistoryDetailRef;
import com.hify.domain.RunHistoryCommit;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.infra.RunHistoryCommitRepository;
import com.hify.runtime.RuntimeMessage;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CanonicalDetailReader {
    private final HistoryDetailRefRepository details;
    private final RunHistoryCommitRepository commits;
    private final ObjectMapper objectMapper;

    public CanonicalDetailReader(HistoryDetailRefRepository details,
                                 RunHistoryCommitRepository commits, ObjectMapper objectMapper) {
        this.details = details; this.commits = commits; this.objectMapper = objectMapper;
    }

    public DetailContent read(String refId) {
        HistoryDetailRef ref = details.findById(refId)
                .orElseThrow(() -> new IllegalArgumentException("History detail ref not found: " + refId));
        RunHistoryCommit commit = commits.findByRunIdAndRevision(ref.getRunId(), ref.getSourceRevision())
                .orElseThrow(() -> new IllegalStateException("Canonical history revision is missing"));
        List<RuntimeMessage> messages = readMessages(commit.getMessagesJson());
        if (ref.getSourceMessageIndex() >= messages.size()) {
            throw new IllegalStateException("Detail ref points outside canonical history");
        }
        RuntimeMessage message = messages.get(ref.getSourceMessageIndex());
        String canonical = write(message);
        if (!MemoryDigests.sha256(canonical).equals(ref.getContentDigest())) {
            throw new IllegalStateException("Detail ref digest does not match canonical history");
        }
        return new DetailContent(ref, message, canonical);
    }

    private List<RuntimeMessage> readMessages(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception exception) { throw new IllegalStateException("Could not read canonical history", exception); }
    }
    private String write(RuntimeMessage message) {
        try { return objectMapper.writeValueAsString(message); }
        catch (Exception exception) { throw new IllegalStateException("Could not verify history detail", exception); }
    }
    public record DetailContent(HistoryDetailRef ref, RuntimeMessage message, String canonicalJson) {}
}
