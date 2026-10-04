package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.workflow.application.WorkflowApplicationService;
import com.hify.workflow.application.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-inputs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password="})
class WorkflowInputsIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired WorkflowEngine engine;
    @Autowired WorkflowApplicationService workflows;

    @Test void publishedSchemaBindsTypedValuesAndRemainsFrozen() throws Exception {
        var graph=graph();String id=create(graph),version=publish(id);
        JsonNode detail=data(http.perform(get("/api/v1/workflow-versions/{id}",version)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode publishedStart=java.util.stream.StreamSupport.stream(detail.path("nodes").spliterator(),false)
                .filter(n->n.path("type").asText().equals("START")).findFirst().orElseThrow();
        assertThat(publishedStart.path("config").path("inputs")).hasSize(4);
        int runs=count("workflow_runs"),nodes=count("workflow_node_runs");
        JsonNode result=run(version,good());
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("output").asText()).isEqualTo("{{entry.flag}}|0|false|a|hello");
        assertThat(result.path("context").path("entry.flag").isBoolean()).isTrue();
        assertThat(result.path("context").path("entry.count").isNumber()).isTrue();
        assertThat(count("workflow_runs")).isEqualTo(runs+1);assertThat(count("workflow_node_runs")).isEqualTo(nodes+2);
        ((ObjectNode)graph.path("nodes").get(0).path("config").path("inputs").get(3)).set("options",json.valueToTree(List.of("b")));
        http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(graph.toString())).andExpect(status().isOk());
        String next=publish(id);
        assertThat(run(version,good()).path("status").asText()).isEqualTo("SUCCEEDED");
        reject(next,good());
        assertThat(data(http.perform(get("/api/v1/workflow-versions/{id}",version)).andReturn().getResponse().getContentAsString())).isEqualTo(detail);
    }

    @Test void invalidInputsRejectBeforeRowsAndDirectServiceCannotBypass() throws Exception {
        String version=publish(create(graph()));
        run(version,good()); // positive control: counters below can actually increase
        int runs=count("workflow_runs"),nodes=count("workflow_node_runs");
        for(String key:List.of("title","flag","pick")) {
            var missing=good();missing.remove(key);reject(version,missing);
            reject(version,good().putNull(key));
        }
        reject(version,good().put("title","x".repeat(65)));
        reject(version,good().put("title","a\u0000b"));
        reject(version,good().put("count","0"));reject(version,good().put("flag","false"));
        reject(version,good().put("count",1000000000001L));reject(version,good().put("pick","unknown"));
        reject(version,good().put("surprise","secret-not-echoed"));reject(version,json.createArrayNode());
        reject(version,json.nullNode());
        http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content("{\"input\":\"hello\"}")).andExpect(status().isBadRequest());
        assertThatThrownBy(()->engine.executeWithInputs(version,"hello",good().put("flag","false"))).isInstanceOf(com.hify.common.BizException.class);
        assertThat(count("workflow_runs")).isEqualTo(runs);assertThat(count("workflow_node_runs")).isEqualTo(nodes);
    }

    @Test void rejectsInvalidSchemaOnUpdateAndUndeclaredReference() throws Exception {
        var graph=graph();String id=create(graph);
        JsonNode original=data(http.perform(get("/api/v1/workflows/{id}",id)).andReturn().getResponse().getContentAsString());
        ((ObjectNode)graph.path("nodes").get(0).path("config").path("inputs").get(0)).put("required","true");
        http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(graph.toString())).andExpect(status().isBadRequest());
        assertThat(data(http.perform(get("/api/v1/workflows/{id}",id)).andReturn().getResponse().getContentAsString())).isEqualTo(original);
        var unknown=graph();((ObjectNode)unknown.path("nodes").get(1).path("config")).put("output","{{entry.undeclared}}");
        http.perform(post("/api/v1/workflows").contentType("application/json").content(unknown.toString())).andExpect(status().isBadRequest());
    }

    @Test void chatRejectsRequiredNamedFieldsButOptionalDefaultsRunThroughPublishedAgent() throws Exception {
        var graph=graph();String wid=create(graph);publish(wid);
        String aid=data(http.perform(post("/api/v1/agents").contentType("application/json").content("""
          {"name":"inputs-agent-%s","instructions":"workflow","providerId":"mock","modelId":"hify-mock","temperature":0.2,"maxTokens":2048,"maxTurns":6,"maxContextTurns":10,"enabledTools":[],"enabled":true}
          """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).asText();
        String binding=json.writeValueAsString(Map.of("workflowId",wid));
        http.perform(put("/api/v1/agents/{id}/workflow-binding",aid).contentType("application/json").content(binding)).andExpect(status().isConflict());
        assertThat(workflows.publishedSnapshots(List.of(wid))).isEmpty();
        for(JsonNode field:graph.path("nodes").get(0).path("config").path("inputs")){
            var f=(ObjectNode)field;f.put("required",false);
            switch(f.path("type").asText()){case "text"->f.put("default","default");case "boolean"->f.put("default",false);case "enum"->f.put("default","a");default->f.put("default",0);}
        }
        http.perform(put("/api/v1/workflows/{id}",wid).contentType("application/json").content(graph.toString())).andExpect(status().isOk());
        publish(wid);assertThat(workflows.publishedSnapshots(List.of(wid))).containsKey(wid);
        http.perform(put("/api/v1/agents/{id}/workflow-binding",aid).contentType("application/json").content(binding)).andExpect(status().isOk());
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isOk());
        String cid=json.readTree(http.perform(post("/api/v1/conversations").contentType("application/json").content(json.writeValueAsString(Map.of("agentId",aid,"title","inputs")))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        int before=count("workflow_runs");
        String rid=json.readTree(http.perform(post("/api/v1/conversations/{id}/runs",cid).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"message\":\"hello\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        JsonNode run=null;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){run=json.readTree(http.perform(get("/api/v1/runs/{id}",rid)).andReturn().getResponse().getContentAsString());if(!run.path("state").asText().equals("RUNNING"))break;Thread.sleep(25);}
        assertThat(run.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("outputMessage").asText()).isEqualTo("default|0|false|a|hello");
        assertThat(count("workflow_runs")).isEqualTo(before+1);
        // Binding happened while all fields were optional. A newer publication
        // must be rechecked when the Agent is subsequently published.
        var title=(ObjectNode)graph.path("nodes").get(0).path("config").path("inputs").get(0);
        title.put("required",true);title.remove("default");
        http.perform(put("/api/v1/workflows/{id}",wid).contentType("application/json").content(graph.toString())).andExpect(status().isOk());
        publish(wid);
        http.perform(post("/api/v1/agents/{id}/publications",aid)).andExpect(status().isConflict());
    }

    @Test void oldUnmarkedInputConfigIsRejectedButLegacyUserMessageStillRuns() throws Exception {
        String id=create(graph()), version=publish(id);
        String raw=db.queryForObject("select dsl_json from workflow_versions where id=?",String.class,version);
        var unmarked=(ObjectNode)json.readTree(raw);unmarked.remove("publication");String dsl=unmarked.toString();
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(dsl.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("update workflow_versions set dsl_json=?, checksum=? where id=?",dsl,sha,version);
        int before=count("workflow_runs");reject(version,good());assertThat(count("workflow_runs")).isEqualTo(before);
        var legacy=graph();((ObjectNode)legacy.path("nodes").get(0).path("config")).remove("inputs");
        ((ObjectNode)legacy.path("nodes").get(1).path("config")).put("output","{{entry.userMessage}}");
        String old=publish(create(legacy));
        JsonNode response=data(http.perform(post("/api/v1/workflow-versions/{id}/runs",old).contentType("application/json").content("{\"input\":\"legacy\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("output").asText()).isEqualTo("legacy");assertThat(count("workflow_runs")).isEqualTo(before+1);
    }

    @Test void rawJsonDecimalsKeepPrecisionThroughHttpAndPublishedDefaults() throws Exception {
        String exact="0.12345678901234567890123";
        var graph=graph();
        ((ObjectNode)graph.path("nodes").get(0).path("config").path("inputs").get(1)).put("default",new java.math.BigDecimal(exact));
        String version=publish(create(graph));
        assertThat(run(version,good()).path("output").asText()).isEqualTo("{{entry.flag}}|"+exact+"|false|a|hello");
        for(String raw:List.of(exact,"1e12","1e-7","1e-400","999999999999.9999999","0.0","-0.0")) {
            String body="{\"input\":\"hello\",\"inputs\":{\"title\":\"x\",\"count\":"+raw+",\"flag\":false,\"pick\":\"a\"}}";
            JsonNode response=data(http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
            assertThat(response.path("output").asText()).as(raw).isEqualTo("x|"+new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()+"|false|a|hello");
        }
        int runs=count("workflow_runs"),nodes=count("workflow_node_runs");
        for(String raw:List.of("1000000000000.0000001","-1000000000000.0000001","1e-1001","NaN","{\"nested\":[1]}","null")) {
            String body="{\"input\":\"hello\",\"inputs\":{\"title\":\"x\",\"count\":"+raw+",\"flag\":false,\"pick\":\"a\"}}";
            http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(count("workflow_runs")).isEqualTo(runs);assertThat(count("workflow_node_runs")).isEqualTo(nodes);
    }

    @Test void requiredTextWhitespaceAndUtf16LimitsAgreeWithBrowser() throws Exception {
        String version=publish(create(graph()));
        for(String text:List.of("x".repeat(64),"😀".repeat(32),"\u200b"))
            assertThat(run(version,good().put("title",text)).path("status").asText()).isEqualTo("SUCCEEDED");
        int before=count("workflow_runs");
        for(String text:List.of("\u00a0","\ufeff"," \t\n","😀".repeat(32)+"x"))reject(version,good().put("title",text));
        assertThat(count("workflow_runs")).isEqualTo(before);
    }

    private ObjectNode graph() throws Exception {return (ObjectNode)json.readTree("""
      {"name":"inputs-%s","schemaVersion":1,"nodes":[
       {"nodeKey":"entry","type":"START","name":"开始","config":{"inputs":[
        {"name":"title","label":"标题","type":"text","required":true,"maxLength":64},
        {"name":"count","type":"number","required":false,"default":0},
        {"name":"flag","type":"boolean","required":true},
        {"name":"pick","type":"enum","required":true,"options":["a","b"]}]}},
       {"nodeKey":"end","type":"END","name":"结束","config":{"output":"{{entry.title}}|{{entry.count}}|{{entry.flag}}|{{entry.pick}}|{{entry.userMessage}}"}}],
       "edges":[{"edgeKey":"edge","sourceNodeKey":"entry","targetNodeKey":"end"}]}
      """.formatted(UUID.randomUUID()));}
    private ObjectNode good(){return json.createObjectNode().put("title","{{entry.flag}}").put("flag",false).put("pick","a");}
    private int count(String table){return db.queryForObject("select count(*) from "+table,Integer.class);}
    private JsonNode data(String body)throws Exception{return json.readTree(body).path("data");}
    private String create(ObjectNode graph)throws Exception{return data(http.perform(post("/api/v1/workflows").contentType("application/json").content(graph.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).asText();}
    private String publish(String id)throws Exception{return data(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asText();}
    private JsonNode run(String id,JsonNode inputs)throws Exception{return data(http.perform(post("/api/v1/workflow-versions/{id}/runs",id).contentType("application/json").content(json.createObjectNode().put("input","hello").set("inputs",inputs).toString())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());}
    private void reject(String id,JsonNode inputs)throws Exception{http.perform(post("/api/v1/workflow-versions/{id}/runs",id).contentType("application/json").content(json.createObjectNode().put("input","hello").set("inputs",inputs).toString())).andExpect(status().isBadRequest());}
}
