package com.hify.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
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
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:workflow-graph-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password="})
class WorkflowGraphBoundaryIntegrationTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;

    @Test void pathLimitIsEnforcedBeforeSaveAndTheBoundaryActuallyExecutes() throws Exception {
        ObjectNode fifty=chain(50);String id=create(fifty),version=publish(id);
        var result=execute(version,"input");
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(result.path("nodes")).hasSize(50);
        ObjectNode tooLong=chain(51);
        http.perform(post("/api/v1/workflows").contentType("application/json").content(tooLong.toString())).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(tooLong.toString())).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select count(*) from workflow_nodes where workflow_id=?",Integer.class,id)).isEqualTo(50);
    }

    @Test void quotedKeywordRunsCorrectlyAndOldInvalidDraftCannotPublish() throws Exception {
        String keyword="a contains b == c \"quoted\"";
        var draft=condition("{{start.userMessage}} == "+json.writeValueAsString(keyword));
        String id=create(draft),version=publish(id);
        assertThat(execute(version,keyword).path("output").asText()).isEqualTo("true");
        assertThat(execute(version,"other").path("output").asText()).isEqualTo("false");
        for(String bad:List.of("hello","a ==","'a' == 'a' == 'a'")){
            http.perform(post("/api/v1/workflows").contentType("application/json").content(condition(bad).toString())).andExpect(status().isBadRequest());
        }
        db.update("update workflow_nodes set config_json=? where workflow_id=? and node_key='route'","{\"expression\":\"hello\"}",id);
        http.perform(post("/api/v1/workflows/{id}/validations",id)).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select count(*) from workflow_versions where workflow_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void legacyInvalidConditionIsAWorkflowErrorNotModelErrorAndCannotBeBound() throws Exception {
        String id=create(condition("true")),version=publish(id),agent=agent();
        bind(agent,id);http.perform(post("/api/v1/agents/{id}/publications",agent)).andExpect(status().isOk());
        String conversation=read(http.perform(post("/api/v1/conversations").contentType("application/json")
                .content(json.writeValueAsString(Map.of("agentId",agent,"title","old workflow")))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText();
        // Simulate a checksum-valid version published by the old permissive validator.
        String legacy=condition("hello").toString();String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("update workflow_versions set dsl_json=?, checksum=? where id=?",legacy,sha,version);
        http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content("{\"input\":\"hello\"}")).andExpect(status().isBadRequest());
        http.perform(put("/api/v1/agents/{id}/workflow-binding",agent()).contentType("application/json").content(json.writeValueAsString(Map.of("workflowId",id)))).andExpect(status().isBadRequest());
        String run=read(http.perform(post("/api/v1/conversations/{id}/runs",conversation).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content("{\"message\":\"hello\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("id").asText();
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end && "RUNNING".equals(db.queryForObject("select state from agent_runs where id=?",String.class,run)))Thread.sleep(10);
        assertThat(db.queryForObject("select terminal_reason from agent_runs where id=?",String.class,run)).isEqualTo("WORKFLOW_ERROR");
        assertThat(db.queryForObject("select count(*) from workflow_runs where workflow_version_id=?",Integer.class,version)).isZero();
        assertThat(db.queryForObject("select count(*) from chat_messages where conversation_id=? and role='assistant'",Integer.class,conversation)).isZero();
        assertThat(db.queryForObject("select dsl_json from workflow_versions where id=?",String.class,version)).isEqualTo(legacy);
    }

    private ObjectNode chain(int count){
        ObjectNode draft=json.createObjectNode().put("name","graph-"+UUID.randomUUID()).put("schemaVersion",1);
        ArrayNode nodes=draft.putArray("nodes"),edges=draft.putArray("edges");
        for(int i=0;i<count;i++){
            String key=i==0?"start":i==count-1?"end":"n"+i;
            ObjectNode config=nodes.addObject().put("nodeKey",key).put("name",key).put("type",i==0?"START":i==count-1?"END":"TEMPLATE").putObject("config");
            if(i>0)config.put(i==count-1?"output":"template","done");
            if(i>0)edges.addObject().put("edgeKey","e"+i).put("sourceNodeKey",i==1?"start":"n"+(i-1)).put("targetNodeKey",key);
        }return draft;
    }
    private ObjectNode condition(String expression){
        ObjectNode graph=chain(3);ObjectNode node=(ObjectNode)graph.path("nodes").get(1);node.put("nodeKey","route").put("type","CONDITION");node.putObject("config").put("expression",expression);
        ((ObjectNode)graph.path("nodes").get(2).path("config")).put("output","{{route.result}}");
        ((ObjectNode)graph.path("edges").get(0)).put("targetNodeKey","route");
        ((ObjectNode)graph.path("edges").get(1)).put("sourceNodeKey","route").put("defaultBranch",true);return graph;
    }
    private String create(ObjectNode graph) throws Exception{return read(http.perform(post("/api/v1/workflows").contentType("application/json").content(graph.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();}
    private String publish(String id) throws Exception{return read(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();}
    private JsonNode execute(String version,String input) throws Exception{return read(http.perform(post("/api/v1/workflow-versions/{id}/runs",version).contentType("application/json").content(json.writeValueAsString(Map.of("input",input)))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");}
    private String agent() throws Exception{return read(http.perform(post("/api/v1/agents").contentType("application/json").content("""
        {"name":"graph-agent-%s","instructions":"workflow","providerId":"mock","modelId":"hify-mock","temperature":0.2,"maxTokens":2048,"maxTurns":6,"maxContextTurns":10,"enabledTools":[],"enabled":true}
        """.formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();}
    private void bind(String agent,String workflow) throws Exception{http.perform(put("/api/v1/agents/{id}/workflow-binding",agent).contentType("application/json").content(json.writeValueAsString(Map.of("workflowId",workflow)))).andExpect(status().isOk());}
    private JsonNode read(String raw) throws Exception{return json.readTree(raw);}
}
