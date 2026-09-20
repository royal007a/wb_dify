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
