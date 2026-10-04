package com.hify.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.CircuitBreakerService;
import com.hify.common.LlmHttpClient;
import com.hify.provider.api.ProviderRuntimeConfig;
import com.hify.provider.api.ProviderType;
import com.hify.provider.application.ProviderAuthConfigCodec;
import com.hify.provider.runtime.CredentialResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NativeProviderModelClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void explicitOutputBudgetIsMappedWithoutChangingLegacyDefaults() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        String baseUrl=serve("/", exchange -> {
            captured.set(json.readTree(exchange.getRequestBody()));
            respond(exchange,"{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}],\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}");
        });
        for(ProviderType type:List.of(ProviderType.OPENAI,ProviderType.ANTHROPIC,ProviderType.GEMINI)) {
            ModelClient client=switch(type){
                case OPENAI -> new OpenAiCompatibleModelClient(config(type,baseUrl),json,http(),resilience(),codec(),credential());
                case ANTHROPIC -> new AnthropicModelClient(config(type,baseUrl),json,http(),resilience(),codec(),credential());
                default -> new GeminiModelClient(config(type,baseUrl),json,http(),resilience(),codec(),credential());
            };
            assertThat(client.generate(new ModelRequest("budget",0,List.of(RuntimeMessage.user("hi")),List.of(),com.hify.common.ExecutionControl.none(),123)).content()).isEqualTo("OK");
            assertThat(type==ProviderType.GEMINI?captured.get().path("generationConfig").path("maxOutputTokens").asInt():captured.get().path("max_tokens").asInt()).isEqualTo(123);
            client.generate(new ModelRequest("budget",0,List.of(RuntimeMessage.user("hi")),List.of()));
            if(type==ProviderType.ANTHROPIC)assertThat(captured.get().path("max_tokens").asInt()).isEqualTo(1024);
            else assertThat(type==ProviderType.GEMINI?captured.get().path("generationConfig").has("maxOutputTokens"):captured.get().has("max_tokens")).isFalse();
        }
    }

    @Test
    void mapsAnthropicToolUseAndResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> secondRequest = new AtomicReference<>();
        String baseUrl = serve("/v1/messages", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (calls.getAndIncrement() == 0) {
                assertThat(request.path("system").asText()).isEqualTo("Be concise");
                assertThat(request.path("tools").get(0).path("input_schema").path("type").asText()).isEqualTo("object");
                respond(exchange, "{\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"calculator\",\"input\":{\"expression\":\"2+2\"}}]}");
            } else {
                secondRequest.set(request);
                respond(exchange, "{\"content\":[{\"type\":\"text\",\"text\":\"4\"}]}");
            }
        });
        ModelClient client = new AnthropicModelClient(config(ProviderType.ANTHROPIC, baseUrl), json,
                http(), resilience(), codec(), credential());
        ToolDefinition tool = tool();
        RuntimeMessage requested = client.generate(new ModelRequest("claude-test", 0.2,
                List.of(RuntimeMessage.system("Be concise"), RuntimeMessage.user("2+2")), List.of(tool)));
        assertThat(requested.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_1");
            assertThat(call.name()).isEqualTo("calculator");
            assertThat(call.arguments()).containsEntry("expression", "2+2");
        });
        RuntimeMessage answer = client.generate(new ModelRequest("claude-test", 0.2,
                List.of(RuntimeMessage.user("2+2"), requested, RuntimeMessage.toolResult("toolu_1", 4, false)),
                List.of(tool)));
        assertThat(answer.content()).isEqualTo("4");
        JsonNode result = secondRequest.get().path("messages").get(2).path("content").get(0);
        assertThat(result.path("type").asText()).isEqualTo("tool_result");
        assertThat(result.path("tool_use_id").asText()).isEqualTo("toolu_1");
    }

    @Test
    void mapsGeminiFunctionCallAndResponse() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> secondRequest = new AtomicReference<>();
        String baseUrl = serve("/models/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (calls.getAndIncrement() == 0) {
                assertThat(request.path("systemInstruction").path("parts").get(0).path("text").asText())
                        .isEqualTo("Be concise");
                assertThat(request.path("tools").get(0).path("functionDeclarations").get(0)
                        .path("parameters").path("type").asText()).isEqualTo("object");
                respond(exchange, "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"id\":\"call_1\",\"name\":\"calculator\",\"args\":{\"expression\":\"2+2\"}}}]}}]}");
            } else {
                secondRequest.set(request);
                respond(exchange, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"4\"}]}}]}");
            }
        });
        ModelClient client = new GeminiModelClient(config(ProviderType.GEMINI, baseUrl), json,
                http(), resilience(), codec(), credential());
        ToolDefinition tool = tool();
        RuntimeMessage requested = client.generate(new ModelRequest("gemini-test", 0.2,
                List.of(RuntimeMessage.system("Be concise"), RuntimeMessage.user("2+2")), List.of(tool)));
        assertThat(requested.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call_1");
            assertThat(call.name()).isEqualTo("calculator");
        });
        RuntimeMessage answer = client.generate(new ModelRequest("gemini-test", 0.2,
                List.of(RuntimeMessage.user("2+2"), requested, RuntimeMessage.toolResult("call_1", 4, false)),
                List.of(tool)));
        assertThat(answer.content()).isEqualTo("4");
        JsonNode result = secondRequest.get().path("contents").get(2).path("parts").get(0).path("functionResponse");
        assertThat(result.path("id").asText()).isEqualTo("call_1");
        assertThat(result.path("name").asText()).isEqualTo("calculator");
    }

    @Test
    void streamsOpenAiTextAndReassemblesToolArguments() throws Exception {
        String baseUrl = serve("/chat/completions", exchange -> respondSse(exchange, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"2+2\\\"}\"}}]}}]}",
                "[DONE]")));
        ModelClient client = new OpenAiCompatibleModelClient(config(ProviderType.OPENAI, baseUrl), json,
                http(), resilience(), codec(), credential());
        StringBuilder deltas = new StringBuilder();
        RuntimeMessage result = client.generateStream(new ModelRequest("gpt-test", 0.2,
                List.of(RuntimeMessage.user("hello")), List.of(tool())), deltas::append);
        assertThat(deltas.toString()).isEqualTo("Hello");
        assertThat(result.content()).isEqualTo("Hello");
        assertThat(result.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("expression", "2+2"));
    }

    @Test
    void streamsAnthropicTextDeltas() throws Exception {
        String baseUrl = serve("/v1/messages", exchange -> respondSse(exchange, List.of(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude\"}}",
                "{\"type\":\"message_stop\"}")));
        ModelClient client = new AnthropicModelClient(config(ProviderType.ANTHROPIC, baseUrl), json,
                http(), resilience(), codec(), credential());
        StringBuilder deltas = new StringBuilder();
        RuntimeMessage result = client.generateStream(new ModelRequest("claude-test", 0.2,
                List.of(RuntimeMessage.user("hello")), List.of()), deltas::append);
        assertThat(deltas.toString()).isEqualTo("Hello Claude");
        assertThat(result.content()).isEqualTo("Hello Claude");
    }

    @Test
    void streamsGeminiTextDeltas() throws Exception {
        String baseUrl = serve("/models/", exchange -> respondSse(exchange, List.of(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello \"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini\"}]}}]}")));
        ModelClient client = new GeminiModelClient(config(ProviderType.GEMINI, baseUrl), json,
                http(), resilience(), codec(), credential());
        StringBuilder deltas = new StringBuilder();
        RuntimeMessage result = client.generateStream(new ModelRequest("gemini-test", 0.2,
                List.of(RuntimeMessage.user("hello")), List.of()), deltas::append);
        assertThat(deltas.toString()).isEqualTo("Hello Gemini");
        assertThat(result.content()).isEqualTo("Hello Gemini");
    }

    @Test
    void recordsFirstTokenP95AndUsageEvidence() throws Exception {
        String baseUrl = serve("/chat/completions", exchange -> respondSse(exchange, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}",
                "[DONE]")));
        ModelClient client = new OpenAiCompatibleModelClient(config(ProviderType.OPENAI, baseUrl), json,
                http(), resilience(), codec(), credential());
        List<Long> firstTokenMs = new ArrayList<>();
        AtomicReference<ModelUsage> usage = new AtomicReference<>();
        for (int index = 0; index < 10; index++) {
            long started = System.nanoTime();
            AtomicReference<Long> first = new AtomicReference<>();
            client.generateStream(new ModelRequest("gpt-test", 0.2,
                    List.of(RuntimeMessage.user("hello")), List.of()), new ModelStreamObserver() {
                @Override public void onTextDelta(String delta) {
                    first.compareAndSet(null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                }
                @Override public void onUsage(ModelUsage value) { usage.set(value); }
            });
            firstTokenMs.add(first.get());
        }
        Collections.sort(firstTokenMs);
        long p95 = firstTokenMs.get((int) Math.ceil(firstTokenMs.size() * 0.95) - 1);
        assertThat(p95).isLessThan(3_000);
        assertThat(usage.get()).isEqualTo(new ModelUsage(7, 2, 9));
        System.out.printf("PROVIDER_STREAM_EVAL {\"samples\":%d,\"firstTokenP95Ms\":%d,"
                + "\"usage\":{\"input\":%d,\"output\":%d,\"total\":%d}}%n",
                firstTokenMs.size(), p95, usage.get().inputTokens(), usage.get().outputTokens(),
                usage.get().totalTokens());
    }

    private String serve(String path, ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try { handler.handle(exchange); }
            catch (Throwable failure) { respond(exchange, 500, failure.toString()); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ProviderRuntimeConfig config(ProviderType type, String baseUrl) {
        String header = type == ProviderType.ANTHROPIC ? "x-api-key" : "x-goog-api-key";
        return new ProviderRuntimeConfig("test", "Test", type, baseUrl,
                "{\"version\":1,\"credentialRef\":\"env:TEST\",\"headerName\":\"" + header + "\",\"prefix\":\"\"}",
                "test-model", true);
    }

    private ProviderAuthConfigCodec codec() { return new ProviderAuthConfigCodec(json); }
    private CredentialResolver credential() { return (ignored, endpoint) -> "test-key"; }
    private LlmHttpClient http() { return new LlmHttpClient(Runnable::run); }
    private CircuitBreakerService resilience() {
        return new CircuitBreakerService(CircuitBreakerRegistry.ofDefaults(), Runnable::run,
                Duration.ofSeconds(5), 1, Duration.ZERO, 1, Duration.ZERO);
    }
    private ToolDefinition tool() {
        return new ToolDefinition("calculator", "Calculate", Map.of("type", "object",
                "properties", Map.of("expression", Map.of("type", "string"))), "read");
    }
    private static void respond(HttpExchange exchange, String body) throws IOException { respond(exchange, 200, body); }
    private static void respondSse(HttpExchange exchange, List<String> events) throws IOException {
        String body = events.stream().map(event -> "data: " + event + "\n\n")
                .reduce("", String::concat);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    @FunctionalInterface interface ExchangeHandler { void handle(HttpExchange exchange) throws Exception; }
}
