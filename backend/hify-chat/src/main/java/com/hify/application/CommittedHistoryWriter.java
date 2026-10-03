package com.hify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.RunHistoryCommit;
import com.hify.infra.RunHistoryCommitRepository;
import com.hify.runtime.HistoryCommitter;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.HistoryReplayException;
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
import java.util.Optional;

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
        return new HistoryCommitter(){
            public CommitReceipt commit(String operationId,List<RuntimeMessage> messages){return CommittedHistoryWriter.this.commit(runId,operationId,messages);}
            public CommitReceipt commitTool(String operationId,List<RuntimeMessage> messages,ToolReplay replay){return CommittedHistoryWriter.this.commit(runId,operationId,messages,replay);}
            public Optional<Replay> replay(String operationId,List<RuntimeMessage> prefix){return CommittedHistoryWriter.this.replay(runId,operationId,prefix);}
        };
    }

    public HistoryCommitter.CommitReceipt commit(String runId, String operationId,
                                                  List<RuntimeMessage> sourceMessages) {
        return commit(runId,operationId,sourceMessages,null);
    }

    private HistoryCommitter.CommitReceipt commit(String runId,String operationId,List<RuntimeMessage> sourceMessages,
                                                   HistoryCommitter.ToolReplay recovery) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("History operation id is required");
        }
        // Serialization captures the semantic snapshot before any asynchronous boundary.
        String snapshot = write(List.copyOf(sourceMessages));
        String digest = sha256(snapshot);
        String recoveryJson=recovery==null?null:writeRecovery(recovery);
        CommitMutation mutation = transactions.execute(status -> mutateAndReread(
                runId, operationId, snapshot, digest,recoveryJson));
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
                                           String snapshot, String digest,String recoveryJson) {
        RunHistoryCommit existing = commits.findByRunIdAndOperationId(runId, operationId).orElse(null);
        if (existing != null) {
            if (!existing.getSemanticDigest().equals(digest)) {
                throw new HistoryOperationConflictException(runId, operationId);
            }
            if(recoveryJson!=null && !recoveryJson.equals(existing.getRecoveryJson()))throw new HistoryOperationConflictException(runId,operationId);
            return new CommitMutation(existing, true);
        }
        long revision = commits.findTopByRunIdOrderByRevisionDesc(runId)
                .map(value -> value.getRevision() + 1).orElse(1L);
        var row=new RunHistoryCommit(runId, revision, operationId,digest, snapshot, Instant.now());
        if(recoveryJson!=null)row.bindRecovery(recoveryJson,sha256(digest+"\n"+recoveryJson));
        commits.saveAndFlush(row);
        RunHistoryCommit reread = commits.findByRunIdAndRevision(runId, revision)
                .orElseThrow(() -> new IllegalStateException("Committed history could not be reread"));
        if (!digest.equals(reread.getSemanticDigest()) || !snapshot.equals(reread.getMessagesJson())
                ||!java.util.Objects.equals(recoveryJson,reread.getRecoveryJson())) {
            throw new IllegalStateException("Committed history reread did not match semantic snapshot");
        }
        return new CommitMutation(reread, false);
    }

    private Optional<HistoryCommitter.Replay> replay(String runId,String operationId,List<RuntimeMessage> prefix){
        return commits.findByRunIdAndOperationId(runId,operationId).map(row->{
            try {
                if(!sha256(row.getMessagesJson()).equals(row.getSemanticDigest()))throw new HistoryReplayException("Canonical history digest mismatch");
                var history=objectMapper.readTree(row.getMessagesJson());
                var before=objectMapper.readTree(write(prefix));
                if(!history.isArray()||history.size()!=before.size()+1)throw new HistoryReplayException("Canonical operation is not the expected append");
                for(int i=0;i<before.size();i++)if(!before.get(i).equals(history.get(i)))throw new HistoryReplayException("Canonical history prefix mismatch");
                RuntimeMessage message=objectMapper.treeToValue(history.get(history.size()-1),RuntimeMessage.class);
                HistoryCommitter.ToolReplay recovery=null;
                if(row.getRecoveryJson()!=null){
                    if(!sha256(row.getSemanticDigest()+"\n"+row.getRecoveryJson()).equals(row.getRecoveryDigest()))throw new HistoryReplayException("Tool recovery digest mismatch");
                    recovery=objectMapper.readValue(row.getRecoveryJson(),HistoryCommitter.ToolReplay.class);
                }
                return new HistoryCommitter.Replay(message,recovery);
            }catch(HistoryReplayException failure){throw failure;}
            catch(Exception failure){throw new HistoryReplayException("Canonical history recovery data is invalid");}
        });
    }

    private String writeRecovery(HistoryCommitter.ToolReplay recovery){
        try{return objectMapper.writeValueAsString(recovery);}
        catch(Exception failure){throw new HistoryReplayException("Cannot serialize tool recovery state");}
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
