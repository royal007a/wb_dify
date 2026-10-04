package com.hify.api;
import com.fasterxml.jackson.databind.*; import com.hify.agent.api.*; import com.hify.common.*; import com.hify.mcp.application.McpEndpointGuard; import com.hify.runtime.*; import com.sun.net.httpserver.*; import org.junit.jupiter.api.*; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc; import org.springframework.boot.test.context.SpringBootTest; import org.springframework.test.context.TestPropertySource; import org.springframework.test.web.servlet.MockMvc; import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.util.*; import static org.assertj.core.api.Assertions.*; import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:hify-mcp-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","hify.mcp.allow-private=true"})
class McpServerApiIntegrationTest {
 @org.springframework.test.context.DynamicPropertySource static void referenceGrants(org.springframework.test.context.DynamicPropertyRegistry registry){
  registry.add("hify.credentials.reference-bindings",()->"{\"env:MCP_EDIT_TEST_TOKEN\":[\"http://127.0.0.1:"+port+"/mcp\"],\"system:mcp.test-token\":[\"http://127.0.0.1:"+port+"/mcp\"],\"env:MCP_NEW_TOKEN\":[\"http://127.0.0.1:"+port+"/mcp\"]}");
 }
 static final java.util.concurrent.atomic.AtomicReference<String> driftField=new java.util.concurrent.atomic.AtomicReference<>("orderId");
 static HttpServer remote; static int port; @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired AgentQueryService agents; @Autowired ToolRuntime toolRuntime;
 @BeforeAll static void server()throws Exception{remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);port=remote.getAddress().getPort();remote.createContext("/mcp",McpServerApiIntegrationTest::handle);remote.createContext("/mcp-drift",McpServerApiIntegrationTest::handle);remote.start();}
 @AfterAll static void stop(){remote.stop(0);}

 @Test void discoversVersionedToolsAndOnlyExecutesReadTools()throws Exception{
  String payload="{\"name\":\"local-catalog\",\"endpointUrl\":\"http://127.0.0.1:"+port+"/mcp\",\"enabled\":true}";
  String id=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  JsonNode first=body(http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(first).hasSize(2);assertThat(first.get(0).path("serverRevision").asLong()).isEqualTo(1);
  JsonNode second=body(http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(second.get(0).path("serverRevision").asLong()).isEqualTo(2);
  JsonNode call=body(http.perform(post("/api/v1/mcp-servers/{id}/tools/lookup_order:call",id).contentType("application/json").content("{\"arguments\":{\"orderId\":\"A-1\"}}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");assertThat(call.path("result").path("content").get(0).path("text").asText()).isEqualTo("order:A-1");assertThat(call.path("schemaDigest").asText()).hasSize(64);
  http.perform(post("/api/v1/mcp-servers/{id}/tools/lookup_order:call",id).contentType("application/json").content("{\"arguments\":{}}")).andExpect(status().isBadRequest());
  http.perform(post("/api/v1/mcp-servers/{id}/tools/submit_refund:call",id).contentType("application/json").content("{\"arguments\":{}}")).andExpect(status().isForbidden());
 }

 @Test void listsCurrentToolsAndArchiveRemovesOnlyTheActiveServer()throws Exception{
  String name="catalog-archive-"+UUID.randomUUID();
  String payload=json.createObjectNode().put("name",name).put("endpointUrl","http://127.0.0.1:"+port+"/mcp").put("enabled",true).toString();
  String id=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  JsonNode before=body(http.perform(get("/api/v1/mcp-servers")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(before.toString()).contains(id,name);
  JsonNode empty=body(http.perform(get("/api/v1/mcp-servers/{id}/tools",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(empty).isEmpty();
  JsonNode discovered=body(http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  JsonNode listed=body(http.perform(get("/api/v1/mcp-servers/{id}/tools",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(listed).hasSize(2).isEqualTo(discovered);
  assertThat(listed.toString()).contains("lookup_order","submit_refund","schemaDigest","inputSchema");
  http.perform(delete("/api/v1/mcp-servers/{id}",id)).andExpect(status().isOk());
  JsonNode after=body(http.perform(get("/api/v1/mcp-servers")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
  assertThat(after.toString()).doesNotContain(id,name);
  http.perform(get("/api/v1/mcp-servers/{id}",id)).andExpect(status().isNotFound());
  http.perform(get("/api/v1/mcp-servers/{id}/tools",id)).andExpect(status().isNotFound());
 }

 @Test void blocksPrivateEndpointsByDefault(){assertThatThrownBy(()->new McpEndpointGuard(false).validate("http://127.0.0.1:8080/mcp")).isInstanceOf(BizException.class);}
 @Test void directTokenFailsClosedWithoutStorageKey()throws Exception{
  var request=json.createObjectNode().put("name","unconfigured-"+UUID.randomUUID()).put("endpointUrl","http://127.0.0.1:"+port+"/mcp").put("enabled",true).put("credentialAction","TOKEN").put("credentialToken","fake-secret-without-master-key");
  String error=http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(request.toString())).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
  assertThat(error).contains("HIFY_MCP_MASTER_KEY").doesNotContain("fake-secret-without-master-key");
  String list=http.perform(get("/api/v1/mcp-servers")).andReturn().getResponse().getContentAsString();assertThat(list).doesNotContain(request.path("name").asText());
 }
 @Test void validatesCredentialReferencesOnBothCreateAndUpdate()throws Exception{
  var request=json.createObjectNode().put("name","edit-"+UUID.randomUUID()).put("endpointUrl","http://127.0.0.1:"+port+"/mcp").put("enabled",true);
  for(String invalid:List.of("plaintext-test-token","Bearer fake-token","env:","system:","env:BAD NAME","env:1INVALID")){
   request.put("credentialRef",invalid);
   http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(request.toString())).andExpect(status().isBadRequest());
  }
  request.put("credentialRef"," env:MCP_EDIT_TEST_TOKEN ");
  String id=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(request.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  request.put("credentialRef","plaintext-test-token");
  String error=http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
  assertThat(error).contains("env:").doesNotContain("plaintext-test-token");
  assertThat(getServer(id).path("credentialRef").asText()).isEqualTo("env:MCP_EDIT_TEST_TOKEN");
  for(String valid:List.of("system:mcp.test-token","", "  ")){
   request.put("credentialRef",valid);
   http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isOk());
  }
  request.put("name","renamed-"+id).put("enabled",false);
  http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isOk());
  JsonNode edited=getServer(id);assertThat(edited.path("name").asText()).isEqualTo("renamed-"+id);assertThat(edited.path("enabled").asBoolean()).isFalse();assertThat(edited.path("credentialRef").isNull()).isTrue();
 }

 @Test void connectionEditsRequireDiscoveryButRenamingDoesNot()throws Exception{
  var request=json.createObjectNode().put("name","edit-ready-"+UUID.randomUUID()).put("endpointUrl","http://127.0.0.1:"+port+"/mcp").put("enabled",true);
  String id=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(request.toString())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",id)).andExpect(status().isOk());
  String digest=getServer(id).path("schemaDigest").asText();request.put("name","renamed-ready-"+id);
  http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isOk());
  assertThat(getServer(id).path("status").asText()).isEqualTo("READY");assertThat(getServer(id).path("schemaDigest").asText()).isEqualTo(digest);
  request.put("credentialRef","env:MCP_NEW_TOKEN");
  http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isOk());
  JsonNode edited=getServer(id);assertThat(edited.path("status").asText()).isEqualTo("NEW");assertThat(edited.path("serverRevision").asInt()).isEqualTo(1);assertThat(edited.path("schemaDigest").isNull()).isTrue();
  http.perform(post("/api/v1/mcp-servers/{id}/tools/lookup_order:call",id).contentType("application/json").content("{\"arguments\":{\"orderId\":\"A-1\"}}")).andExpect(status().isConflict());
  request.put("credentialRef","");http.perform(put("/api/v1/mcp-servers/{id}",id).contentType("application/json").content(request.toString())).andExpect(status().isOk());
  http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",id)).andExpect(status().isOk());assertThat(getServer(id).path("serverRevision").asInt()).isEqualTo(2);
 }
 private JsonNode getServer(String id)throws Exception{return body(http.perform(get("/api/v1/mcp-servers/{id}",id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
 @Test void keepsPublishedAgentVersionOnItsFrozenSchemaAfterServerDrift()throws Exception{
  driftField.set("orderId");String suffix=UUID.randomUUID().toString().substring(0,8);String payload="{\"name\":\"drift-"+suffix+"\",\"endpointUrl\":\"http://127.0.0.1:"+port+"/mcp-drift\",\"enabled\":true}";
  String serverId=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",serverId)).andExpect(status().isOk());
  String agentPayload="{\"name\":\"Drift Agent "+suffix+"\",\"instructions\":\"Use tools\",\"providerId\":\"mock\",\"modelId\":\"hify-mock\",\"temperature\":0.2,\"maxTokens\":1024,\"maxTurns\":4,\"maxContextTurns\":10,\"enabledTools\":[],\"enabled\":true}";
  String agentId=body(http.perform(post("/api/v1/agents").contentType("application/json").content(agentPayload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();http.perform(put("/api/v1/agents/{id}/mcp-bindings",agentId).contentType("application/json").content("{\"bindings\":[{\"serverId\":\""+serverId+"\",\"toolNames\":[\"lookup_order\"]}]}" )).andExpect(status().isOk());
  String oldVersionId=body(http.perform(post("/api/v1/agents/{id}/publications",agentId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();var oldTool=agents.requireVersion(oldVersionId).mcpTools().get(0);assertThat(oldTool.inputSchema().get("required")).isEqualTo(List.of("orderId"));
  driftField.set("reference");http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",serverId)).andExpect(status().isOk());String newVersionId=body(http.perform(post("/api/v1/agents/{id}/publications",agentId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();var newTool=agents.requireVersion(newVersionId).mcpTools().get(0);assertThat(newVersionId).isNotEqualTo(oldVersionId);assertThat(newTool.inputSchema().get("required")).isEqualTo(List.of("reference"));assertThat(newTool.toolSchemaDigest()).isNotEqualTo(oldTool.toolSchemaDigest());
  var oldDefinition=new ToolDefinition(oldTool.runtimeToolName(),oldTool.description(),oldTool.inputSchema(),"read");var oldCapability=toolRuntime.snapshot(oldVersionId,Set.of(oldTool.runtimeToolName()),List.of(oldDefinition));var result=toolRuntime.execute(new RuntimeMessage.ToolCall("old-schema-call",oldTool.runtimeToolName(),Map.of("orderId","OLD-1")),oldCapability,ToolExecutionLease.local("old-schema-call",oldCapability.revision()),ExecutionControl.none());assertThat(result.error()).isFalse();assertThat(result.value().toString()).contains("OLD-1");
 }
 @Test void freezesReadSchemaIntoAgentVersionAndExecutesThroughQueryLoopRuntime()throws Exception{
  String suffix=UUID.randomUUID().toString().substring(0,8);String payload="{\"name\":\"runtime-"+suffix+"\",\"endpointUrl\":\"http://127.0.0.1:"+port+"/mcp\",\"enabled\":true}";
  String serverId=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",serverId)).andExpect(status().isOk());
  String agentPayload="{\"name\":\"MCP Agent "+suffix+"\",\"instructions\":\"Use tools\",\"providerId\":\"mock\",\"modelId\":\"hify-mock\",\"temperature\":0.2,\"maxTokens\":1024,\"maxTurns\":4,\"maxContextTurns\":10,\"enabledTools\":[],\"enabled\":true}";
  String agentId=body(http.perform(post("/api/v1/agents").contentType("application/json").content(agentPayload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(put("/api/v1/agents/{id}/mcp-bindings",agentId).contentType("application/json").content("{\"bindings\":[{\"serverId\":\""+serverId+"\",\"toolNames\":[\"lookup_order\"]}]}" )).andExpect(status().isOk());
  String versionId=body(http.perform(post("/api/v1/agents/{id}/publications",agentId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
  var agent=agents.requireVersion(versionId);assertThat(agent.mcpTools()).singleElement().satisfies(tool->{assertThat(tool.serverRevision()).isEqualTo(1);assertThat(tool.toolSchemaDigest()).hasSize(64);assertThat(tool.risk()).isEqualTo("READ");});
  http.perform(put("/api/v1/agents/{id}/mcp-bindings",agentId).contentType("application/json").content("{\"bindings\":[{\"serverId\":\""+serverId+"\",\"toolNames\":[\"submit_refund\"]}]}" )).andExpect(status().isForbidden());
  var edit=json.createObjectNode().put("name","runtime-"+suffix).put("endpointUrl","http://127.0.0.1:1/unavailable").put("enabled",true);
  http.perform(put("/api/v1/mcp-servers/{id}",serverId).contentType("application/json").content(edit.toString())).andExpect(status().isOk());
  assertThat(getServer(serverId).path("status").asText()).isEqualTo("NEW");
  http.perform(post("/api/v1/agents/{id}/publications",agentId)).andExpect(status().isConflict());
  var tool=agent.mcpTools().get(0);var definition=new ToolDefinition(tool.runtimeToolName(),tool.description(),tool.inputSchema(),"read");var capability=toolRuntime.snapshot(versionId,Set.of(tool.runtimeToolName()),List.of(definition));
  var result=toolRuntime.execute(new RuntimeMessage.ToolCall("mcp-call",tool.runtimeToolName(),Map.of("orderId","A-1")),capability,ToolExecutionLease.local("mcp-call",capability.revision()),ExecutionControl.none());
  assertThat(result.error()).isFalse();assertThat(result.value().toString()).contains("order:A-1");
 }
 private JsonNode body(String value)throws Exception{return json.readTree(value);}
 private static void handle(HttpExchange x)throws IOException{String request=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(request.contains("notifications/initialized")){x.sendResponseHeaders(202,-1);x.close();return;}String response;if(request.contains("\"method\":\"initialize\""))response="{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}";else if(request.contains("\"method\":\"tools/list\"")){String field=x.getRequestURI().getPath().endsWith("drift")?driftField.get():"orderId";response="{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[{\"name\":\"lookup_order\",\"description\":\"Lookup order\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\""+field+"\":{\"type\":\"string\"}},\"required\":[\""+field+"\"]},\"annotations\":{\"readOnlyHint\":true}},{\"name\":\"submit_refund\",\"description\":\"Refund\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":false}}]}}";}else {String order=request.contains("OLD-1")?"OLD-1":request.contains("A-1")?"A-1":"unknown";response="{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"order:"+order+"\"}],\"isError\":false}}";}byte[] bytes=response.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("mcp-session-id","fake-session");x.sendResponseHeaders(200,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}}
}
