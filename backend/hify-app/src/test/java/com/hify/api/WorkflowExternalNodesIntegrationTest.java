package com.hify.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.*;
import com.hify.workflow.api.*;
import com.hify.workflow.application.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-external;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","hify.provider.allow-private=true",
        "hify.resilience.timeout-max-attempts=1"})
class WorkflowExternalNodesIntegrationTest {
    static final ObjectMapper mapper = new ObjectMapper();
    static final AtomicInteger modelCalls = new AtomicInteger(), httpCalls = new AtomicInteger(), redirectCalls = new AtomicInteger();
    static final AtomicReference<String> mode = new AtomicReference<>("ok"), rawQuery = new AtomicReference<>();
    static final AtomicReference<JsonNode> modelRequest = new AtomicReference<>();
    static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    static final ExecutorService serverPool = Executors.newCachedThreadPool(r -> { var t=new Thread(r);t.setDaemon(true);return t; });
    static final HttpServer server = start();
    static HttpServer start() { try {
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); s.setExecutor(serverPool);
        s.createContext("/v1/chat/completions", e -> {
            modelCalls.incrementAndGet();modelRequest.set(mapper.readTree(e.getRequestBody()));
            String current=mode.get();
            if(current.equals("slow")){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException x){Thread.currentThread().interrupt();}}
            Object message = current.equals("tools") ? Map.of("role","assistant","tool_calls",List.of(Map.of("id","x","type","function","function",Map.of("name","danger","arguments","{}"))))
                    : Map.of("role","assistant","content",current.equals("large")?"x".repeat(32769):"固定模型答案");
            byte[] body=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",message))));
            try{e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}finally{e.close();}
        });
        s.createContext("/read", e -> {
            httpCalls.incrementAndGet();rawQuery.set(e.getRequestURI().getRawQuery());String current=mode.get();
            if(current.equals("slow")){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException x){Thread.currentThread().interrupt();}}
            byte[] body=(current.equals("large")?"x".repeat(32769):current.equals("echo")?"synthetic-workflow-secret":"HTTP 原文").getBytes(StandardCharsets.UTF_8);
            try{e.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
                if(current.equals("redirect")){e.getResponseHeaders().set("Location","/private");e.sendResponseHeaders(302,-1);}
                else{e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);}
            }finally{e.close();}
        });
        s.createContext("/private",e->{redirectCalls.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        s.start();return s;
    }catch(Exception x){throw new IllegalStateException(x);} }
    static String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    @DynamicPropertySource static void grants(DynamicPropertyRegistry p){
        System.setProperty("WORKFLOW_TEST_KEY","synthetic-workflow-secret");
        p.add("hify.credentials.reference-bindings",()->"{\"system:WORKFLOW_TEST_KEY\":[\""+base()+"/v1\",\""+base()+"/read\"]}");
        p.add("hify.workflow.http-allowed-endpoints",()->"[\""+base()+"/read\"]");
    }
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired WorkflowApplicationService workflows; @Autowired WorkflowEngine engine;
    @BeforeEach void reset(){mode.set("ok");entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    @AfterEach void unblock(){release.countDown();}
    @AfterAll static void stop(){server.stop(0);serverPool.shutdownNow();System.clearProperty("WORKFLOW_TEST_KEY");}
    String provider() throws Exception {
        return json.readTree(http.perform(post("/api/v1/providers").contentType("application/json").content(json.writeValueAsString(Map.of(
                "name","workflow-"+UUID.randomUUID(),"type","OPENAI_COMPATIBLE","baseUrl",base()+"/v1",
                "auth",Map.of("credentialRef","system:WORKFLOW_TEST_KEY"),"models",List.of(Map.of("displayName","fixture","modelId","fixture","enabled",true,"isDefault",true))))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
    }
    ObjectNode llm() throws Exception {return json.valueToTree(Map.of("providerId",provider(),"modelId","fixture","prompt","{{entry.userMessage}}","maxOutputTokens",123));}
    ObjectNode api(){return json.valueToTree(Map.of("endpoint",base()+"/read","method","GET","query",Map.of("q","{{entry.userMessage}}"),"credentialRef","system:WORKFLOW_TEST_KEY"));}
    WorkflowDraftRequest graph(String type,ObjectNode config){return new WorkflowDraftRequest("ext-"+UUID.randomUUID(),"",1,
            List.of(new WorkflowNodeSpec("entry","START","开始",json.createObjectNode()),new WorkflowNodeSpec("external",type,"外部",config),
                    new WorkflowNodeSpec("end","END","结束",json.createObjectNode().put("output","{{external.result}}"))),
            List.of(new WorkflowEdgeSpec("e1","entry","external",null,false),new WorkflowEdgeSpec("e2","external","end",null,false)));}
    String publish(String type,ObjectNode config){return workflows.publish(workflows.create(graph(type,config))).id();}
    int rows(String table){return db.queryForObject("select count(*) from "+table,Integer.class);}

    @Test void llmIsFrozenAtPublicationAndHasPositiveExecutionTrace() throws Exception {
        var config=llm();String version=publish("LLM",config);int before=rows("workflow_runs"), nodes=rows("workflow_node_runs");
        var result=engine.execute(version,"hello");
        assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.output()).isEqualTo("固定模型答案");
        assertThat(rows("workflow_runs")).isEqualTo(before+1);assertThat(rows("workflow_node_runs")).isEqualTo(nodes+3);
        assertThat(modelRequest.get().path("max_tokens").asInt()).isEqualTo(123);assertThat(modelRequest.get().has("tools")).isFalse();
        String stored=db.queryForObject("select dsl_json from workflow_versions where id=?",String.class,version);
        assertThat(stored).contains("modelSnapshot","externalNodeFormat").doesNotContain("synthetic-workflow-secret");
        db.update("update providers set base_url=? where public_id=?",base()+"/other",config.path("providerId").asText());
        assertThat(engine.execute(version,"again").output()).isEqualTo("固定模型答案");
    }
    @Test void snapshotForgeryAndNonUpstreamPromptAreRejectedBeforeWrites() throws Exception {
        var config=llm();int count=rows("workflows"), calls=modelCalls.get();config.set("modelSnapshot",json.createObjectNode());
        assertThatThrownBy(()->workflows.create(graph("LLM",config))).isInstanceOf(BizException.class);
        config.remove("modelSnapshot");config.put("prompt","{{external.result}}");
        assertThatThrownBy(()->workflows.create(graph("LLM",config))).isInstanceOf(BizException.class);
        assertThat(rows("workflows")).isEqualTo(count);assertThat(modelCalls.get()).isEqualTo(calls);
    }
    @Test void llmRejectsToolCallsAndOversizedOutputAndExpandedInput() throws Exception {
        String version=publish("LLM",llm());
        for(String invalid:List.of("tools","large")){mode.set(invalid);var run=engine.execute(version,"hello");assertThat(run.status()).isEqualTo("FAILED");assertThat(run.output()).isNull();}
        int before=modelCalls.get();assertThat(engine.execute(version,"x".repeat(16001)).status()).isEqualTo("FAILED");assertThat(modelCalls.get()).isEqualTo(before);
    }
    @Test void httpQueryIsEncodedAndRedirectsAndCredentialEchoAreRejected() {
        String version=publish("API_CALL",api());int before=httpCalls.get(),privateBefore=redirectCalls.get();
        assertThat(engine.execute(version,"a&admin=true#fragment").output()).isEqualTo("HTTP 原文");assertThat(httpCalls.get()).isEqualTo(before+1);
        assertThat(rawQuery.get()).isEqualTo("q=a%26admin%3Dtrue%23fragment");
        for(String invalid:List.of("redirect","large","echo")){mode.set(invalid);var run=engine.execute(version,"hello");assertThat(run.status()).isEqualTo("FAILED");assertThat(run.output()).isNull();assertThat(run.errorMessage()).doesNotContain("synthetic-workflow-secret");}
        assertThat(redirectCalls.get()).isEqualTo(privateBefore);
    }
    @Test void httpAuthorityMethodsAndUnapprovedDestinationsAreRejected() {
        int before=httpCalls.get(),versions=rows("workflow_versions");
        var config=api();config.put("endpoint",base()+"/{{entry.userMessage}}");
        assertThatThrownBy(()->workflows.create(graph("API_CALL",config))).isInstanceOf(BizException.class);
        config.put("endpoint",base()+"/read").put("method","POST");
        assertThatThrownBy(()->workflows.create(graph("API_CALL",config))).isInstanceOf(BizException.class);
        config.put("method","GET").put("endpoint",base()+"/private");String id=workflows.create(graph("API_CALL",config));
        assertThatThrownBy(()->workflows.publish(id)).isInstanceOf(BizException.class).hasMessageContaining("精确授权");
        assertThat(rows("workflow_versions")).isEqualTo(versions);assertThat(httpCalls.get()).isEqualTo(before);
    }
    @Test void cancelAndDeadlineStopInFlightLlmAndHttpWithoutEndNode() throws Exception {
        for(String type:List.of("LLM","API_CALL")) {
            String version=publish(type,type.equals("LLM")?llm():api());mode.set("slow");entered=new CountDownLatch(1);release=new CountDownLatch(1);
            AtomicBoolean cancel=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
            try {
                var running=pool.submit(()->engine.execute(version,"wait",ExecutionControl.withTimeout(Duration.ofSeconds(4),cancel::get)));
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();cancel.set(true);
                var stopped=running.get(2,TimeUnit.SECONDS);assertThat(stopped.status()).isEqualTo("CANCELLED");assertThat(stopped.output()).isNull();assertThat(stopped.nodes()).hasSize(2);
                release.countDown();entered=new CountDownLatch(1);release=new CountDownLatch(1);
                var timed=engine.execute(version,"wait",ExecutionControl.withTimeout(Duration.ofMillis(150),()->false));
                assertThat(timed.status()).isEqualTo("TIMED_OUT");assertThat(timed.output()).isNull();
            } finally {release.countDown();pool.shutdownNow();mode.set("ok");}
        }
    }
    @Test void alreadyCancelledExternalNodesMakeNoNetworkCalls() throws Exception {
        for(String type:List.of("LLM","API_CALL")) {
            String version=publish(type,type.equals("LLM")?llm():api());int before=modelCalls.get()+httpCalls.get();
            assertThat(engine.execute(version,"no",ExecutionControl.withTimeout(Duration.ofSeconds(1),()->true)).status()).isEqualTo("CANCELLED");
            assertThat(modelCalls.get()+httpCalls.get()).isEqualTo(before);
        }
    }

    @Test void httpManagementAndExecutionUseSameImmutableGraph() throws Exception {
        var draft=graph("API_CALL",api());
        String id=json.readTree(http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(draft)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        String version=json.readTree(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
        var bad=api().put("method","POST");
        http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(json.writeValueAsString(graph("API_CALL",bad)))).andExpect(status().isBadRequest());
        JsonNode result=json.readTree(http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content("{\"input\":\"合法\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(result.path("output").asText()).isEqualTo("HTTP 原文");
    }

    @Test void disabledProviderCannotBeBypassedByPublishedModelSnapshot() throws Exception {
        var config=llm();String version=publish("LLM",config);
        assertThat(engine.execute(version,"positive").status()).isEqualTo("SUCCEEDED");
        db.update("update providers set enabled=false where public_id=?",config.path("providerId").asText());
        int before=modelCalls.get();var refused=engine.execute(version,"negative");
        assertThat(refused.status()).isEqualTo("FAILED");assertThat(refused.output()).isNull();assertThat(modelCalls.get()).isEqualTo(before);
    }

    @Test void oldClientInjectedExternalGraphCannotExecuteWithoutPublicationStamp() throws Exception {
        String version=publish("LLM",llm());assertThat(engine.execute(version,"positive").status()).isEqualTo("SUCCEEDED");
        ObjectNode stored=(ObjectNode)json.readTree(db.queryForObject("select dsl_json from workflow_versions where id=?",String.class,version));stored.remove("publication");
        String raw=json.writeValueAsString(stored),digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        db.update("update workflow_versions set dsl_json=?,checksum=? where id=?",raw,digest,version);
        int before=rows("workflow_runs"),calls=modelCalls.get();
        assertThatThrownBy(()->engine.execute(version,"negative")).isInstanceOf(WorkflowDefinitionException.class);
        assertThat(rows("workflow_runs")).isEqualTo(before);assertThat(modelCalls.get()).isEqualTo(calls);
    }

    @Test void hostnameToLoopbackAndInvalidCredentialFailWithoutNetworkOrSecretLeak() throws Exception {
        var emptyCredentials=new CredentialReferencePolicy("{}",json);
        String localHostname="http://localhost:"+server.getAddress().getPort()+"/read";
        var client=new WorkflowHttpClient(json.writeValueAsString(List.of(localHostname)),json,emptyCredentials);
        int before=httpCalls.get();
        assertThatThrownBy(()->client.get(localHostname,"",Map.of(),ExecutionControl.withTimeout(Duration.ofSeconds(2),()->false)))
                .isInstanceOf(BizException.class).hasMessage("HTTP 节点调用失败或响应超限");
        assertThat(httpCalls.get()).isEqualTo(before);
        System.setProperty("WORKFLOW_BAD_KEY","private\nnot-a-header");
        try {
            var credentials=new CredentialReferencePolicy(json.writeValueAsString(Map.of("system:WORKFLOW_BAD_KEY",List.of(base()+"/read"))),json);
            var allowed=new WorkflowHttpClient(json.writeValueAsString(List.of(base()+"/read")),json,credentials);
            assertThatThrownBy(()->allowed.get(base()+"/read","system:WORKFLOW_BAD_KEY",Map.of(),ExecutionControl.none()))
                    .isInstanceOf(BizException.class).hasMessage("HTTP 节点凭据格式无效");
            assertThat(httpCalls.get()).isEqualTo(before);
        } finally {System.clearProperty("WORKFLOW_BAD_KEY");}
    }
}
