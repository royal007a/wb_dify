package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.RunHistoryCommit;
import com.hify.infra.RunHistoryCommitRepository;
import com.hify.runtime.HistoryCommitter;
import com.hify.runtime.RuntimeMessage;
import com.hify.memory.CanonicalMemoryIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Durable canonical history writer: snapshot -> mutation -> reread -> revision -> projection -> ack. */
@Service
public class CommittedHistoryWriter {
    private static final Logger log = LoggerFactory.getLogger(CommittedHistoryWriter.class);
    private final RunHistoryCommitRepository commits;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final RunEventBroker events;
    private final CanonicalMemoryIndexer memoryIndexer;

    public CommittedHistoryWriter(RunHistoryCommitRepository commits, TransactionTemplate transactions,
                                  ObjectMapper objectMapper, RunEventBroker events,
                                  CanonicalMemoryIndexer memoryIndexer) {
        this.commits = commits;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.events = events;
        this.memoryIndexer = memoryIndexer;
    }

    public HistoryCommitter forRun(String runId) {
        return (operationId, messages) -> commit(runId, operationId, messages);
    }

    public HistoryCommitter.CommitReceipt commit(String runId, String operationId,
                                                  List<RuntimeMessage> sourceMessages) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("History operation id is required");
        }
        // Serialization captures the semantic snapshot before any asynchronous boundary.
        String snapshot = write(List.copyOf(sourceMessages));
        String digest = sha256(snapshot);
        CommitMutation mutation = transactions.execute(status -> mutateAndReread(
                runId, operationId, snapshot, digest));
        if (mutation == null) throw new IllegalStateException("History commit transaction returned no result");

        // Required projection is completed before acknowledgement is returned to QueryLoop.
        // A committed-but-unprojected row is repaired on replay; projected rows are not emitted twice.
        if (mutation.commit().getProjectedAt() == null) {
            events.publish(runId, "history.committed", Map.of(
                    "version", 1, "runId", runId, "operationId", operationId,
                    "revision", mutation.commit().getRevision(),
                    "semanticDigest", mutation.commit().getSemanticDigest(),
                    "replayed", mutation.replayed()));
            transactions.executeWithoutResult(status -> commits.findById(mutation.commit().getId())
                    .ifPresent(value -> {
                        value.markProjected(Instant.now());
                        commits.save(value);
                    }));
        }
        try {
            memoryIndexer.index(runId, mutation.commit().getRevision(), sourceMessages,
                    mutation.commit().getCommittedAt());
        } catch (RuntimeException exception) {
            // The derived catalog is repairable from canonical history and must never corrupt its commit.
            log.warn("history.memory.index.failed runId={} revision={}", runId,
                    mutation.commit().getRevision(), exception);
        }
        return new HistoryCommitter.CommitReceipt(mutation.commit().getRevision(),
                mutation.commit().getSemanticDigest(), mutation.replayed());
    }

    private CommitMutation mutateAndReread(String runId, String operationId,
                                           String snapshot, String digest) {
        RunHistoryCommit existing = commits.findByRunIdAndOperationId(runId, operationId).orElse(null);
        if (existing != null) {
            if (!existing.getSemanticDigest().equals(digest)) {
                throw new HistoryOperationConflictException(runId, operationId);
            }
            return new CommitMutation(existing, true);
        }
        long revision = commits.findTopByRunIdOrderByRevisionDesc(runId)
                .map(value -> value.getRevision() + 1).orElse(1L);
        commits.saveAndFlush(new RunHistoryCommit(runId, revision, operationId,
                digest, snapshot, Instant.now()));
        RunHistoryCommit reread = commits.findByRunIdAndRevision(runId, revision)
                .orElseThrow(() -> new IllegalStateException("Committed history could not be reread"));
        if (!digest.equals(reread.getSemanticDigest()) || !snapshot.equals(reread.getMessagesJson())) {
            throw new IllegalStateException("Committed history reread did not match semantic snapshot");
        }
        return new CommitMutation(reread, false);
    }

    private String write(List<RuntimeMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize canonical Run history", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record CommitMutation(RunHistoryCommit commit, boolean replayed) {}
}
