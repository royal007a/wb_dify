package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-intent-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class IntentRoutingApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;

    @Test
    void exposesDeterministicDecisionWithoutInvokingTheConfiguredModel() throws Exception {
        JsonNode response = json(http.perform(post("/api/v1/intent-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"demo-agent\",\"input\":\"请计算 12.5 * 4\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(response.path("intent").asText()).isEqualTo("calculate");
        assertThat(response.path("route").asText()).isEqualTo("tool");
        assertThat(response.path("slots").path("toolName").asText()).isEqualTo("calculator");
        assertThat(response.path("slots").path("expression").asText()).isEqualTo("12.5*4");
        assertThat(response.path("evidence").get(0).path("source").asText()).isEqualTo("RULE");
    }

    @Test
    void rejectsBlankInputBeforeRouting() throws Exception {
        String response = http.perform(post("/api/v1/intent-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"demo-agent\",\"input\":\"\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(json(response).path("code").asInt()).isEqualTo(40000);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
