package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hify-provider-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "hify.resilience.timeout-max-attempts=1",
        "hify.resilience.rate-limit-max-attempts=1"
})
class ProviderApiIntegrationTest {
    private static HttpServer providerServer;
    private static String providerBaseUrl;
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicReference<String> anthropicKey = new AtomicReference<>();
    private static final AtomicReference<String> geminiKey = new AtomicReference<>();

    @Autowired MockMvc http;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startProvider() throws IOException {
        System.setProperty("HIFY_TEST_PROVIDER_KEY", "secret-for-test");
        providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerServer.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        });
        providerServer.createContext("/v1/messages", exchange -> {
            anthropicKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            respond(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"stop_reason\":\"end_turn\"}");
        });
        providerServer.createContext("/models/", exchange -> {
            geminiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            respond(exchange, 200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"OK\"}]}}]}");
        });
        providerServer.start();
        providerBaseUrl = "http://127.0.0.1:" + providerServer.getAddress().getPort();
    }

    @AfterAll
    static void stopProvider() {
        if (providerServer != null) providerServer.stop(0);
        System.clearProperty("HIFY_TEST_PROVIDER_KEY");
    }

    @Test
    void deliversCrudModelCatalogAndIndependentHealthCheck() throws Exception {
        String createBody = """
                {
                  "name":"Team Gateway",
                  "type":"OPENAI_COMPATIBLE",
                  "baseUrl":"%s",
                  "auth":{"credentialRef":"system:HIFY_TEST_PROVIDER_KEY"},
                  "models":[
                    {"displayName":"Fast Model","modelId":"model-fast","enabled":true,"isDefault":true},
                    {"displayName":"Deep Model","modelId":"model-deep","enabled":true,"isDefault":false}
                  ]
                }
                """.formatted(providerBaseUrl);
        JsonNode created = json(http.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String providerId = created.path("data").asText();
        assertThat(providerId).isNotBlank();

        JsonNode page = json(http.perform(get("/api/v1/providers?page=1&pageSize=10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(2);
        JsonNode row = findProvider(page.path("data"), providerId);
        assertThat(row.path("defaultModelId").asText()).isEqualTo("model-fast");
        assertThat(row.path("credentialConfigured").asBoolean()).isTrue();
        assertThat(row.has("authConfig")).isFalse();
        assertThat(row.path("health").path("status").asText()).isEqualTo("UNKNOWN");
        String storedAuth = jdbc.queryForObject("SELECT auth_config FROM providers WHERE public_id = ?",
                String.class, providerId);
        assertThat(storedAuth).contains("system:HIFY_TEST_PROVIDER_KEY").doesNotContain("secret-for-test");

        String updateBody = """
                {
                  "name":"Team Gateway",
                  "type":"OPENAI_COMPATIBLE",
                  "baseUrl":"%s",
                  "enabled":true,
                  "models":[
                    {"displayName":"Fast Display Name","modelId":"model-fast","enabled":true,"isDefault":true}
                  ]
                }
                """.formatted(providerBaseUrl);
        http.perform(put("/api/v1/providers/{id}", providerId)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk());
        JsonNode updated = json(http.perform(get("/api/v1/providers/{id}", providerId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(updated.path("data").path("models")).hasSize(1);
        assertThat(updated.path("data").path("models").get(0).path("displayName").asText())
                .isEqualTo("Fast Display Name");
        assertThat(updated.path("data").path("models").get(0).path("modelId").asText())
                .isEqualTo("model-fast");

        JsonNode tested = json(http.perform(post("/api/v1/providers/{id}/connection-tests", providerId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(tested.path("data").path("success").asBoolean()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer secret-for-test");
        JsonNode afterTest = json(http.perform(get("/api/v1/providers/{id}", providerId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(afterTest.path("data").path("health").path("status").asText()).isEqualTo("HEALTHY");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_health h JOIN providers p ON p.id = h.provider_id
                WHERE p.public_id = ? AND h.status = 'HEALTHY'
                """, Integer.class, providerId)).isEqualTo(1);

        http.perform(delete("/api/v1/providers/{id}", providerId)).andExpect(status().isOk());
        http.perform(get("/api/v1/providers/{id}", providerId)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsMockAndMultipleDefaultModels() throws Exception {
        String body = """
                {"name":"Invalid","type":"MOCK","baseUrl":"http://localhost",
                 "auth":{"credentialRef":"env:NOPE"},
                 "models":[{"displayName":"A","modelId":"a","enabled":true,"isDefault":true}]}
                """;
        JsonNode response = json(http.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).isEqualTo(40000);

        String multipleDefaults = """
                {"name":"Invalid Defaults","type":"OPENAI_COMPATIBLE","baseUrl":"http://localhost:8089",
                 "auth":{"credentialRef":"env:NOPE"},
                 "models":[
                   {"displayName":"A","modelId":"a","enabled":true,"isDefault":true},
                   {"displayName":"B","modelId":"b","enabled":true,"isDefault":true}
                 ]}
                """;
        http.perform(post("/api/v1/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(multipleDefaults))
                .andExpect(status().isBadRequest());
    }

    @Test
    void supportsTheThreeNativeProtocols() throws Exception {
        String openAi = createNative("Native OpenAI", "OPENAI", "gpt-test");
        String anthropic = createNative("Native Anthropic", "ANTHROPIC", "claude-test");
        String gemini = createNative("Native Gemini", "GEMINI", "gemini-test");

        for (String providerId : new String[]{openAi, anthropic, gemini}) {
            JsonNode result = json(http.perform(post("/api/v1/providers/{id}/connection-tests", providerId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(result.path("data").path("success").asBoolean()).isTrue();
        }
        assertThat(authorization.get()).isEqualTo("Bearer secret-for-test");
        assertThat(anthropicKey.get()).isEqualTo("secret-for-test");
        assertThat(geminiKey.get()).isEqualTo("secret-for-test");
    }

    private String createNative(String name, String type, String modelId) throws Exception {
        String body = """
                {"name":"%s","type":"%s","baseUrl":"%s",
                 "auth":{"credentialRef":"system:HIFY_TEST_PROVIDER_KEY"},
                 "models":[{"displayName":"Test Model","modelId":"%s","enabled":true,"isDefault":true}]}
                """.formatted(name, type, providerBaseUrl, modelId);
        JsonNode created = json(http.perform(post("/api/v1/providers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.path("data").asText();
    }

    private JsonNode findProvider(JsonNode providers, String id) {
        for (JsonNode provider : providers) if (id.equals(provider.path("id").asText())) return provider;
        throw new AssertionError("Provider not found in page: " + id);
    }

    private JsonNode json(String value) throws Exception { return objectMapper.readTree(value); }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
