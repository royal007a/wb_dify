package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.ChatMessageRepository;
import com.hify.infra.RunEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:submission-identity;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class RunSubmissionIdentityTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager caches;
    @Autowired AgentRunRepository runs;
    @Autowired ChatMessageRepository messages;
    @Autowired RunEventRepository events;
    // Capture dispatch without executing: the HTTP transaction really commits in the database.
    @MockBean(name="runExecutor") Executor executor;

    @AfterEach void restoreProvider() {
        jdbc.update("update providers set enabled=true where public_id='mock'");
        caches.getCache("provider-cache").clear();
    }

    @Test void committedSubmissionReplaysAfterProviderDisabledButDifferentBodyAndNewWorkDoNot() throws Exception {
        String conversation=conversation(), key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        long eventCount=events.count();
        assertThat(jdbc.update("update providers set enabled=false where public_id='mock'")).isEqualTo(1);
        caches.getCache("provider-cache").clear();
        assertThat(create(conversation,key,"hello",200).path("id").asText()).isEqualTo(id);
        assertThat(create(conversation,key,"changed",409).path("code").asInt()).isEqualTo(40901);
        create(conversation,UUID.randomUUID().toString(),"hello",409);
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).hasSize(1);
        assertThat(events.count()).isEqualTo(eventCount);
        verify(executor,times(1)).execute(any());
    }

    @Test void lookupIsReadOnlyScopedAndUncachedEvenWhenProviderDisabled() throws Exception {
        String conversation=conversation(), other=conversation(), key=UUID.randomUUID().toString();
        String id=create(conversation,key,"hello",202).path("id").asText();
        jdbc.update("update providers set enabled=false where public_id='mock'");
        caches.getCache("provider-cache").clear();
        long runCount=runs.count(),messageCount=messages.count(),eventCount=events.count();
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key",key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Vary","Idempotency-Key"));
        for(String candidate:java.util.List.of(other,UUID.randomUUID().toString())) {
            http.perform(get("/api/v1/conversations/{id}/runs/by-key",candidate).header("Idempotency-Key",key))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation)
                        .header("Idempotency-Key",UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertThat(runs.count()).isEqualTo(runCount);
        assertThat(messages.count()).isEqualTo(messageCount);
        assertThat(events.count()).isEqualTo(eventCount);
        verify(executor,times(1)).execute(any());
    }

    @Test void missingLookupNeverCreatesAndRejectsInvalidKeys() throws Exception {
        String conversation=conversation();
        long before=runs.count();
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key","unknown"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation)).andExpect(status().isBadRequest());
        for(String key:java.util.List.of(" ","a".repeat(129))) {
            http.perform(get("/api/v1/conversations/{id}/runs/by-key",conversation).header("Idempotency-Key",key))
                    .andExpect(status().isBadRequest());
        }
        assertThat(runs.count()).isEqualTo(before);
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation)).isEmpty();
        verifyNoInteractions(executor);
    }

    private String conversation() throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"demo-agent\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asText();
    }
    private JsonNode create(String conversation,String key,String message,int status) throws Exception {
        return json.readTree(http.perform(post("/api/v1/conversations/{id}/runs",conversation)
                .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("message",message))))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
}
