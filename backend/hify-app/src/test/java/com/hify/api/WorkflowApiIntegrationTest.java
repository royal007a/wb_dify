package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
