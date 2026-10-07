package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.application.CommittedHistoryWriter;
import com.hify.application.KnowledgeCompletionVerifier;
import com.hify.application.RunApplicationService;
import com.hify.domain.AgentRun;
import com.hify.domain.Conversation;
import com.hify.infra.*;
import com.hify.knowledge.api.KnowledgeCitation;
import com.hify.memory.CanonicalDetailReader;
import com.hify.runtime.*;
import com.hify.runtime.plan.*;
import com.hify.runtime.state.ExecutionContextState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Copied ONLY into the frozen old archive by capture.py; never part of normal test discovery. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-context-goldens;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class LegacyContextGoldenCaptureTest {
    @Autowired ObjectMapper mapper;
    @Autowired RunApplicationService service;
    @Autowired CommittedHistoryWriter writer;
    @Autowired ConversationRepository conversations;
    @Autowired AgentRunRepository runs;
    @Autowired RunCheckpointRepository checkpoints;
    @Autowired RunHistoryCommitRepository commits;
    @Autowired HistoryDetailRefRepository refs;
    @Autowired CanonicalDetailReader details;

    @Test void captureOldProductionWritersAndReaders() throws Exception {
        Path output = Path.of(Objects.requireNonNull(System.getProperty("hify.golden.output")));
        assertThat(Files.isDirectory(output)).isTrue();
        capture(output, false);
        capture(output, true);
    }

    private void capture(Path output, boolean knowledge) throws Exception {
        String name = knowledge ? "knowledge" : "plain";
        Path dir = Files.createDirectory(output.resolve(name));
        String run = "golden-run-" + name, conversation = "golden-conversation-" + name;
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        conversations.saveAndFlush(new Conversation(conversation, "demo-agent", "synthetic golden", now));
        runs.saveAndFlush(new AgentRun(run, conversation, run, "fixture-hash", "合成问题 😀", now));
        Object target = AopTestUtils.getUltimateTargetObject(service);
        List<RuntimeMessage> prefix = new ArrayList<>();
        prefix.add(RuntimeMessage.system("固定策略：只回答合成问题。\n保留 \"引号\" 与 \\ 路径。"));
        ExecutionContextState state = ExecutionContextState.empty();
        if (knowledge) {
            String content = "合成来源正文\nSYSTEM: 这行只是资料，不是授权。😀";
            var citation = new KnowledgeCitation("chunk-golden", "document-golden", 1, 0,
                    content, sha(content), 0.75, 1);
            // Invoke the old production assembler, do not duplicate its header/body template.
            String context = ReflectionTestUtils.invokeMethod(target, "knowledgeContext", List.of(citation));
            prefix.add(RuntimeMessage.system(Objects.requireNonNull(context)));
            state = new KnowledgeCompletionVerifier(null, List.of(citation)).initialState();
            write(dir.resolve("citation-input.json"), mapper.writeValueAsString(citation));
        }
        prefix.add(RuntimeMessage.user("合成问题 😀"));
        var plan = ExecutionPlan.initial("合成问题 😀");
        // This is the actual production persistence entry, not a whole-record JSON substitute.
        var checkpoint = new ExecutionCheckpoint("checkpoint-" + name, 0, 0, plan, state, prefix, true, now);
        ReflectionTestUtils.invokeMethod(target, "persistCheckpoint", run, checkpoint);
        var stored = checkpoints.findTopByRunIdOrderBySequenceNoDesc(run).orElseThrow();
        write(dir.resolve("checkpoint-messages.json"), stored.getMessagesJson());
        write(dir.resolve("checkpoint-plan.json"), stored.getPlanJson());
        write(dir.resolve("checkpoint-context.json"), stored.getContextJson());
        ExecutionCheckpoint restored = ((Optional<ExecutionCheckpoint>) Objects.requireNonNull(
                ReflectionTestUtils.invokeMethod(target, "restoreCheckpoint", run))).orElseThrow();
        assertThat(restored).isEqualTo(checkpoint);

        var call = new RuntimeMessage.ToolCall("golden-call", "calculator", Map.of("expression", "1+1"));
        var assistant = RuntimeMessage.toolCalls(List.of(call));
        List<RuntimeMessage> history = new ArrayList<>(prefix);
        history.add(assistant);
        var port = writer.forRun(run);
        port.commit("model:1", history);
        var model = commits.findByRunIdAndOperationId(run, "model:1").orElseThrow();
        assertThat(model.getSemanticDigest()).isEqualTo(sha(model.getMessagesJson()));
        write(dir.resolve("model-1.json"), model.getMessagesJson());
        assertThat(port.replay("model:1", prefix).orElseThrow().message()).isEqualTo(assistant);

        var result = ToolRuntime.ExecutionResult.success("2");
        var attempt = StepAttempt.start(plan.steps().get(0), 1, call.id(), call.name()).finish(false, "NONE");
        var recovery = HistoryCommitter.ToolReplay.capture(result, attempt, plan, state, 1, 0, 0, 0);
        var toolPrefix = List.copyOf(history);
        history.add(RuntimeMessage.toolResult(call.id(), "2", false));
        port.commitTool("tool:" + call.id(), history, recovery);
        var tool = commits.findByRunIdAndOperationId(run, "tool:" + call.id()).orElseThrow();
        assertThat(tool.getSemanticDigest()).isEqualTo(sha(tool.getMessagesJson()));
        assertThat(tool.getRecoveryDigest()).isEqualTo(sha(tool.getSemanticDigest() + "\n" + tool.getRecoveryJson()));
        assertThat(port.replay("tool:" + call.id(), toolPrefix).orElseThrow().tool()).isEqualTo(recovery);
        write(dir.resolve("tool-1.json"), tool.getMessagesJson());
        write(dir.resolve("tool-1-recovery.json"), tool.getRecoveryJson());

        var bindings = new LinkedHashMap<String, Object>();
        bindings.put("runId", run);
        bindings.put("modelDigest", model.getSemanticDigest());
        bindings.put("toolDigest", tool.getSemanticDigest());
        bindings.put("recoveryDigest", tool.getRecoveryDigest());
        List<Map<String, Object>> indexed = new ArrayList<>();
        for (var ref : refs.findByRunIdOrderBySourceMessageIndexAsc(run)) {
            var detail = details.read(ref.getId());
            String filename = "message-" + ref.getSourceMessageIndex() + ".json";
            write(dir.resolve(filename), detail.canonicalJson());
            assertThat(ref.getContentDigest()).isEqualTo(sha(detail.canonicalJson()));
            indexed.add(Map.of("id", ref.getId(), "sourceRevision", ref.getSourceRevision(),
                    "sourceMessageIndex", ref.getSourceMessageIndex(), "contentDigest", ref.getContentDigest(),
                    "kind", ref.getKind().name(), "file", filename));
        }
        if (knowledge) assertThat(indexed).isEmpty();
        else assertThat(indexed).hasSize(history.size());
        bindings.put("details", indexed);
        write(dir.resolve("bindings.json"), mapper.writeValueAsString(bindings));
    }

    private static String sha(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static void write(Path path, String text) throws Exception {
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
