package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.workflow.domain.WorkflowVersion;
import com.hify.workflow.infrastructure.WorkflowVersionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:hify-workflow-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password="})
class WorkflowApiIntegrationTest {
 @Autowired MockMvc http; @Autowired ObjectMapper json;
 @Autowired WorkflowVersionRepository versions;
 @Autowired com.hify.workflow.application.WorkflowApplicationService application;

 @Test void publishedAggregationMergesBothBranchesWithoutChangingTheFrozenDefinition() throws Exception {
  ObjectNode graph=(ObjectNode)json.readTree(mergedGraph("{{merge.result}}"));
  ObjectNode agg=((ArrayNode)graph.path("nodes")).addObject();
  agg.put("nodeKey","merge").put("type","AGGREGATOR").put("name","汇合");
  agg.putObject("config").putArray("candidates").add("refund.answer").add("other.answer");
  for(JsonNode e:graph.path("edges"))if(e.path("targetNodeKey").asText().equals("endRefund"))((ObjectNode)e).put("targetNodeKey","merge");
  ((ArrayNode)graph.path("edges")).addObject().put("edgeKey","merged-end").put("sourceNodeKey","merge").put("targetNodeKey","endRefund").put("defaultBranch",false);
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(graph)))
    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  JsonNode version=body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString()).path("data");
  String vid=version.path("id").asText(), checksum=version.path("checksum").asText();
  String frozen=versions.findById(vid).orElseThrow().getDslJson();
  for(String input:java.util.List.of("退货","你好")){
   JsonNode result=run(vid,input);
   assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
   assertThat(result.path("workflowDigest").asText()).isEqualTo(checksum);
   assertThat(result.path("output").asText()).isEqualTo(input.equals("退货")?"退款":"普通");
   assertThat(result.path("nodes")).hasSize(5);
   var keys=new java.util.ArrayList<String>();for(JsonNode n:result.path("nodes")){keys.add(n.path("nodeKey").asText());assertThat(n.path("status").asText()).isEqualTo("SUCCEEDED");}
   assertThat(keys).containsExactly("start","route",input.equals("退货")?"refund":"other","merge","endRefund");
  }
  assertThat(application.publishedSnapshots(java.util.List.of(id))).containsKey(id);
  http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(workflow("changed","changed"))).andExpect(status().isOk());
  assertThat(run(vid,"退货").path("output").asText()).isEqualTo("退款");
  var unchanged=versions.findById(vid).orElseThrow();
  assertThat(unchanged.getDslJson()).isEqualTo(frozen);assertThat(unchanged.getChecksum()).isEqualTo(checksum);
 }

 @Test void previouslyAcceptedExtensionFieldsRemainPublishedAndVisibleInCapabilityCatalog() throws Exception {
  ObjectNode old=(ObjectNode)json.readTree(workflow("legacy-refund","legacy-other"));
  ((ObjectNode)old.path("nodes").get(2).path("config")).put("historicalExtension","do-not-tighten");
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(json.writeValueAsString(old)))
    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  JsonNode publication=body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString()).path("data");
  String vid=publication.path("id").asText();
  var stored=versions.findById(vid).orElseThrow();String raw=stored.getDslJson(), digest=stored.getChecksum();
  assertThat(application.publishedSnapshots(java.util.List.of(id))).containsKey(id);
  assertThat(application.publishedSnapshot(id).checksum()).isEqualTo(digest);
  assertThat(run(vid,"退货").path("output").asText()).isEqualTo("legacy-refund");
  assertThat(versions.findById(vid).orElseThrow().getDslJson()).isEqualTo(raw);
  assertThat(versions.findById(vid).orElseThrow().getChecksum()).isEqualTo(digest);
 }

 @Test void rejectsBranchLocalVariablesOnSaveAndUpdate() throws Exception {
  String graph=mergedGraph("{{refund.answer}}");
  http.perform(post("/api/v1/workflows").contentType("application/json").content(graph)).andExpect(status().isBadRequest());
  String valid=mergedGraph("{{route.matched}}");
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(valid))
    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(graph)).andExpect(status().isBadRequest());
  JsonNode version=body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString()).path("data");
  assertThat(run(version.path("id").asText(),"退货").path("output").asText()).isEqualTo("true");
 }

 @Test void rejectsOldInvalidPublishedGraphWithoutRewritingSnapshot() throws Exception {
  String valid=mergedGraph("{{start.userMessage}}");
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(valid))
    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  String legacy=mergedGraph("{{refund.answer}}"), versionId=UUID.randomUUID().toString();
  String checksum=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  versions.saveAndFlush(new WorkflowVersion(versionId,id,1,1,legacy,checksum,Instant.now()));
  http.perform(post("/api/v1/workflow-versions/{id}/runs",versionId).contentType("application/json").content("{\"input\":\"你好\"}"))
    .andExpect(status().isBadRequest());
  var stored=versions.findById(versionId).orElseThrow();
  assertThat(stored.getDslJson()).isEqualTo(legacy);
  assertThat(stored.getChecksum()).isEqualTo(checksum);
 }

 @Test void customStartKeyWorksThroughPublishedHttpExecution() throws Exception {
  String graph=mergedGraph("{{ start.userMessage }}").replace("start", "entry");
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(graph))
    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  String version=body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk())
    .andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
  JsonNode result=run(version,"literal {{refund.answer}}");
  assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
  assertThat(result.path("output").asText()).isEqualTo("literal {{refund.answer}}");
 }

 private String mergedGraph(String output) throws Exception {
  ObjectNode graph=(ObjectNode)json.readTree(workflow("退款","普通"));
  graph.put("name","merge-"+UUID.randomUUID());
  ArrayNode nodes=(ArrayNode)graph.path("nodes");nodes.remove(5);
  ((ObjectNode)nodes.get(4).path("config")).put("output",output);
  ((ObjectNode)graph.path("edges").get(4)).put("targetNodeKey","endRefund");
  return json.writeValueAsString(graph);
 }

 @Test void publishesImmutableVersionAndExecutesBothBranchesWithTrace() throws Exception {
  String workflow=workflow("退款问题","普通问题");
  String id=body(http.perform(post("/api/v1/workflows").contentType("application/json").content(workflow)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  JsonNode loaded=body(http.perform(get("/api/v1/workflows/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(loaded.path("nodes")).hasSize(6);assertThat(loaded.path("edges")).hasSize(5);
  http.perform(post("/api/v1/workflows/{id}/validations",id)).andExpect(status().isOk());
  JsonNode published=body(http.perform(post("/api/v1/workflows/{id}/versions",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  String versionId=published.path("id").asText();String digest=published.path("checksum").asText();assertThat(digest).hasSize(64);

  JsonNode refund=run(versionId,"我想退货");assertThat(refund.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(refund.path("output").asText()).isEqualTo("退款问题");assertThat(refund.path("workflowDigest").asText()).isEqualTo(digest);assertThat(refund.path("nodes")).hasSize(4);
  JsonNode other=run(versionId,"你好");assertThat(other.path("output").asText()).isEqualTo("普通问题");

  http.perform(put("/api/v1/workflows/{id}",id).contentType("application/json").content(workflow("新退款答复","新普通答复"))).andExpect(status().isOk());
  JsonNode oldStillStable=run(versionId,"我要退货");assertThat(oldStillStable.path("output").asText()).isEqualTo("退款问题");
 }

 @Test void rejectsMissingDefaultDanglingEdgeAndCycle() throws Exception {
  String missingDefault=workflow("A","B").replace("\"defaultBranch\":true","\"defaultBranch\":false");
  http.perform(post("/api/v1/workflows").contentType("application/json").content(missingDefault)).andExpect(status().isBadRequest());
  String dangling=workflow("A","B").replace("\"targetNodeKey\":\"other\"","\"targetNodeKey\":\"missing\"");
  http.perform(post("/api/v1/workflows").contentType("application/json").content(dangling)).andExpect(status().isBadRequest());
  String cycle=workflow("A","B").replace("\"sourceNodeKey\":\"refund\",\"targetNodeKey\":\"endRefund\"","\"sourceNodeKey\":\"refund\",\"targetNodeKey\":\"route\"");
  http.perform(post("/api/v1/workflows").contentType("application/json").content(cycle)).andExpect(status().isBadRequest());
 }

 private JsonNode run(String versionId,String input)throws Exception{return body(http.perform(post("/api/v1/workflow-versions/{id}/runs",versionId).contentType("application/json").content("{\"input\":\""+input+"\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");}
 private String workflow(String refund,String other){return """
 {"name":"客服分流-%s","description":"确定性分支","schemaVersion":1,
  "nodes":[
   {"nodeKey":"start","type":"START","name":"开始","config":{}},
   {"nodeKey":"route","type":"CONDITION","name":"识别退货","config":{"expression":"{{start.userMessage}} contains 退货","outputVariable":"matched"}},
   {"nodeKey":"refund","type":"TEMPLATE","name":"退款答复","config":{"template":"%s","outputVariable":"answer"}},
   {"nodeKey":"other","type":"TEMPLATE","name":"普通答复","config":{"template":"%s","outputVariable":"answer"}},
   {"nodeKey":"endRefund","type":"END","name":"退款结束","config":{"output":"{{refund.answer}}"}},
   {"nodeKey":"endOther","type":"END","name":"普通结束","config":{"output":"{{other.answer}}"}}
  ],
  "edges":[
   {"edgeKey":"e1","sourceNodeKey":"start","targetNodeKey":"route","defaultBranch":false},
   {"edgeKey":"e2","sourceNodeKey":"route","targetNodeKey":"refund","condition":"true","defaultBranch":false},
   {"edgeKey":"e3","sourceNodeKey":"route","targetNodeKey":"other","defaultBranch":true},
   {"edgeKey":"e4","sourceNodeKey":"refund","targetNodeKey":"endRefund","defaultBranch":false},
   {"edgeKey":"e5","sourceNodeKey":"other","targetNodeKey":"endOther","defaultBranch":false}
  ]}
 }
 """.formatted(refund,refund,other);}
 private JsonNode body(String value)throws Exception{return json.readTree(value);}
}
