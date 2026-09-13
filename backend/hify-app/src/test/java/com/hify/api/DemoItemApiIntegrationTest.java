package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-demo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class DemoItemApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void validatesCreatesPagesUpdatesAndLogicallyDeletes() throws Exception {
        JsonNode invalid = json(http.perform(post("/api/v1/demo-items")
                        .header("X-Request-Id", "demo-validation-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"status\":1}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());
        assertThat(invalid.path("code").asInt()).isEqualTo(40000);
        assertThat(invalid.path("message").asText()).contains("name 不能为空");

        JsonNode created = json(http.perform(post("/api/v1/demo-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试项\",\"status\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.path("data").asLong();
        assertThat(id).isPositive();

        JsonNode page = json(http.perform(get("/api/v1/demo-items?page=1&pageSize=10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("code").asInt()).isEqualTo(200);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        JsonNode item = page.path("data").get(0);
        assertThat(item.path("name").asText()).isEqualTo("测试项");
        assertThat(item.path("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

        http.perform(put("/api/v1/demo-items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"已更新\",\"status\":2}"))
                .andExpect(status().isOk());
        JsonNode updated = json(http.perform(get("/api/v1/demo-items/{id}", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(updated.path("data").path("name").asText()).isEqualTo("已更新");

        http.perform(delete("/api/v1/demo-items/{id}", id)).andExpect(status().isOk());
        JsonNode missing = json(http.perform(get("/api/v1/demo-items/{id}", id))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        assertThat(missing.path("code").asInt()).isEqualTo(40400);
        assertThat(jdbc.queryForObject("SELECT deleted FROM demo_items WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
