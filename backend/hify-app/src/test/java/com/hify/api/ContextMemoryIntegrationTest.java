package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.ContextSummary;
import com.hify.domain.ContextSummaryStatus;
import com.hify.domain.HistoryDetailRef;
import com.hify.domain.SummaryClaimStatus;
import com.hify.infra.HistoryDetailRefRepository;
import com.hify.memory.CanonicalDetailReader;
import com.hify.memory.StructuredSummaryService;
import com.hify.runtime.CapabilitySnapshot;
import com.hify.runtime.HistoryCommitter;
import com.hify.runtime.ModelClient;
import com.hify.runtime.QueryLoop;
import com.hify.runtime.RunRuntimeIdentity;
import com.hify.runtime.RuntimeMessage;
import com.hify.runtime.TerminalReason;
import com.hify.runtime.ToolRuntime;
import com.hify.runtime.context.ContextManager;
import com.hify.runtime.state.EvidenceItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-memory-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "hify.run-timeout=5s"
})
@AutoConfigureMockMvc
class ContextMemoryIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;
    @Autowired HistoryDetailRefRepository details;
    @Autowired StructuredSummaryService summaries;
    @Autowired CanonicalDetailReader reader;
    @Autowired JdbcTemplate jdbc;
    @Autowired ToolRuntime toolRuntime;
    @Autowired ContextManager contextManager;

    @Test
    void derivesVerifiableDetailsAndSourceBoundStructuredSummaryFromCanonicalHistory() throws Exception {
        JsonNode conversation = json(http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"demo-agent\",\"title\":\"Memory contract\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String conversationId = conversation.path("id").asText();
        JsonNode created = json(http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "memory-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"必须保留计算依据，请计算 21 * 2\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String runId = created.path("id").asText();
        awaitTerminal(runId);

        List<HistoryDetailRef> refs = details.findByRunIdOrderBySourceMessageIndexAsc(runId);
        assertThat(refs).hasSizeGreaterThanOrEqualTo(4);
        assertThat(refs).extracting(HistoryDetailRef::getRole)
                .contains("system", "user", "assistant", "tool");
        assertThat(reader.read(refs.get(1).getId()).message().content()).contains("必须保留计算依据");

        ContextSummary summary = summaries.createCheckpoint(runId,
                refs.get(refs.size() - 1).getSourceMessageIndex());
        assertThat(summary.getStatus()).isEqualTo(ContextSummaryStatus.CURRENT);
        assertThat(summary.getContentJson()).contains("hify.model-readable-summary.v1",
                "goal", "facts", "constraints", "sourceRefs");
        assertThat(summary.getConstraintsJson()).contains("必须保留计算依据");

        String sourceRef = refs.get(1).getId();
        assertThat(summaries.appendClaim(summary.getId(), "meeting.preference", "FACT",
                "偏好上午", List.of(sourceRef)).getStatus()).isEqualTo(SummaryClaimStatus.VERIFIED);
        summaries.appendClaim(summary.getId(), "meeting.preference", "FACT",
                "偏好下午", List.of(sourceRef));
        assertThat(summaries.validate(summary.getId()).status()).isEqualTo(ContextSummaryStatus.CONFLICTING);

        assertThat(summaries.appendClaim(summary.getId(), "unsupported", "INFERENCE",
                "没有来源的推断", List.of()).getStatus()).isEqualTo(SummaryClaimStatus.MISSING_SOURCE);
        assertThat(summaries.validate(summary.getId()).status()).isEqualTo(ContextSummaryStatus.INVALID);

        jdbc.update("update history_detail_refs set content_digest = ? where id = ?", "0".repeat(64), sourceRef);
        assertThatThrownBy(() -> reader.read(sourceRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void layersRecentTurnsAndRequiresSearchThenCanonicalDetailForVerifiedEvidence() throws Exception {
        JsonNode conversation = json(http.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"demo-agent\",\"title\":\"Recall contract\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String conversationId = conversation.path("id").asText();
        JsonNode created = json(http.perform(post("/api/v1/conversations/{id}/runs", conversationId)
                        .header("Idempotency-Key", "recall-contract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"必须记住暗号 mint-42，并计算 8 * 8\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String runId = created.path("id").asText();
        awaitTerminal(runId);
        HistoryDetailRef target = details.findByRunIdOrderBySourceMessageIndexAsc(runId).stream()
                .filter(ref -> ref.getContentPreview().contains("mint-42")).findFirst().orElseThrow();

        JsonNode search = json(http.perform(post("/api/v1/runs/{runId}/memory/search", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"mint-42\",\"limit\":5}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(search.path("matches").get(0).path("refId").asText()).isEqualTo(target.getId());
        JsonNode detail = json(http.perform(get("/api/v1/runs/{runId}/memory/details/{refId}",
                        runId, target.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("content").asText()).contains("mint-42");

        CapabilitySnapshot capability = toolRuntime.snapshot("recall-test",
                Set.of("history.search", "history.detail"));
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean sawLayeredContext = new AtomicBoolean();
        AtomicReference<String> searchedRef = new AtomicReference<>();
        ModelClient model = request -> switch (calls.incrementAndGet()) {
            case 1 -> {
                sawLayeredContext.set(request.messages().stream().anyMatch(message ->
                        message.content() != null && message.content().contains("HIFY_CONTEXT_MEMORY")));
                yield RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                        "recall-search", "history.search", Map.of("query", "mint-42", "limit", 5))));
            }
            case 2 -> {
                searchedRef.set(target.getId());
                yield RuntimeMessage.toolCalls(List.of(new RuntimeMessage.ToolCall(
                        "recall-detail", "history.detail", Map.of("ref", target.getId()))));
            }
            default -> RuntimeMessage.assistant("暗号是 mint-42，有 canonical evidence 支撑。");
        };
        List<RuntimeMessage> longConversation = List.of(
                RuntimeMessage.system("You are evidence driven"),
                RuntimeMessage.user("第一轮：暗号 mint-42"), RuntimeMessage.assistant("收到"),
                RuntimeMessage.user("第二轮：继续"), RuntimeMessage.assistant("继续"),
                RuntimeMessage.user("第三轮：检查"), RuntimeMessage.assistant("检查"),
                RuntimeMessage.user("第四轮：回答"));
        QueryLoop.Result result = new QueryLoop(toolRuntime, contextManager).run(longConversation,
                model, "mock", 0, capability,
                new QueryLoop.RunPolicy(5, 5, 16_384, 2, 1,
                        4, 2_048, 5_000, Duration.ofSeconds(5), () -> false),
                QueryLoop.RunObserver.NOOP,
                new RunRuntimeIdentity(runId, capability.revision(), capability.toolSchemaDigest(),
                        (attempt, revision) -> true, HistoryCommitter.NOOP));

        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        assertThat(sawLayeredContext).isTrue();
        assertThat(searchedRef).hasValue(target.getId());
        assertThat(result.contextState().evidence()).extracting(EvidenceItem::status)
                .contains(EvidenceItem.Status.UNVERIFIED, EvidenceItem.Status.VERIFIED);
        assertThat(result.contextState().evidence()).filteredOn(item ->
                        item.status() == EvidenceItem.Status.VERIFIED)
                .anyMatch(item -> item.sourceRef().equals(target.getId()));
        assertThat(result.contextState().openBlockingGaps()).isEmpty();
    }

    private JsonNode awaitTerminal(String runId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        JsonNode run;
        do {
            String body = http.perform(get("/api/v1/runs/{id}", runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            run = json(body);
            if (!"RUNNING".equals(run.path("state").asText())) return run;
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not finish");
    }

    private JsonNode json(String value) throws Exception { return objectMapper.readTree(value); }
}
