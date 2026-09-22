package com.hify.api;
import com.fasterxml.jackson.databind.*; import com.hify.agent.api.*; import com.hify.common.*; import com.hify.mcp.application.McpEndpointGuard; import com.hify.runtime.*; import com.sun.net.httpserver.*; import org.junit.jupiter.api.*; import org.springframework.beans.factory.annotation.Autowired; import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc; import org.springframework.boot.test.context.SpringBootTest; import org.springframework.test.context.TestPropertySource; import org.springframework.test.web.servlet.MockMvc; import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.util.*; import static org.assertj.core.api.Assertions.*; import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*; import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:hify-mcp-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","hify.mcp.allow-private=true"})
class McpServerApiIntegrationTest {
 static HttpServer remote; static int port; @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired AgentQueryService agents; @Autowired ToolRuntime toolRuntime;
 @BeforeAll static void server()throws Exception{remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);port=remote.getAddress().getPort();remote.createContext("/mcp",McpServerApiIntegrationTest::handle);remote.start();}
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

 @Test void blocksPrivateEndpointsByDefault(){assertThatThrownBy(()->new McpEndpointGuard(false).validate("http://127.0.0.1:8080/mcp")).isInstanceOf(BizException.class);}
 @Test void freezesReadSchemaIntoAgentVersionAndExecutesThroughQueryLoopRuntime()throws Exception{
  String suffix=UUID.randomUUID().toString().substring(0,8);String payload="{\"name\":\"runtime-"+suffix+"\",\"endpointUrl\":\"http://127.0.0.1:"+port+"/mcp\",\"enabled\":true}";
  String serverId=body(http.perform(post("/api/v1/mcp-servers").contentType("application/json").content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(post("/api/v1/mcp-servers/{id}/tools:refresh",serverId)).andExpect(status().isOk());
  String agentPayload="{\"name\":\"MCP Agent "+suffix+"\",\"instructions\":\"Use tools\",\"providerId\":\"mock\",\"modelId\":\"hify-mock\",\"temperature\":0.2,\"maxTokens\":1024,\"maxTurns\":4,\"maxContextTurns\":10,\"enabledTools\":[],\"enabled\":true}";
  String agentId=body(http.perform(post("/api/v1/agents").contentType("application/json").content(agentPayload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
  http.perform(put("/api/v1/agents/{id}/mcp-bindings",agentId).contentType("application/json").content("{\"bindings\":[{\"serverId\":\""+serverId+"\",\"toolNames\":[\"lookup_order\"]}]}" )).andExpect(status().isOk());
  String versionId=body(http.perform(post("/api/v1/agents/{id}/publications",agentId)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("id").asText();
  var agent=agents.requireVersion(versionId);assertThat(agent.mcpTools()).singleElement().satisfies(tool->{assertThat(tool.serverRevision()).isEqualTo(1);assertThat(tool.toolSchemaDigest()).hasSize(64);assertThat(tool.risk()).isEqualTo("READ");});
  var tool=agent.mcpTools().get(0);var definition=new ToolDefinition(tool.runtimeToolName(),tool.description(),tool.inputSchema(),"read");var capability=toolRuntime.snapshot(versionId,Set.of(tool.runtimeToolName()),List.of(definition));
  var result=toolRuntime.execute(new RuntimeMessage.ToolCall("mcp-call",tool.runtimeToolName(),Map.of("orderId","A-1")),capability,ToolExecutionLease.local("mcp-call",capability.revision()),ExecutionControl.none());
  assertThat(result.error()).isFalse();assertThat(result.value().toString()).contains("order:A-1");
  http.perform(put("/api/v1/agents/{id}/mcp-bindings",agentId).contentType("application/json").content("{\"bindings\":[{\"serverId\":\""+serverId+"\",\"toolNames\":[\"submit_refund\"]}]}" )).andExpect(status().isForbidden());
 }
 private JsonNode body(String value)throws Exception{return json.readTree(value);}
 private static void handle(HttpExchange x)throws IOException{String request=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(request.contains("notifications/initialized")){x.sendResponseHeaders(202,-1);x.close();return;}String response;if(request.contains("\"method\":\"initialize\""))response="{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}";else if(request.contains("\"method\":\"tools/list\""))response="{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"tools\":[{\"name\":\"lookup_order\",\"description\":\"Lookup order\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]},\"annotations\":{\"readOnlyHint\":true}},{\"name\":\"submit_refund\",\"description\":\"Refund\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":false}}]}}";else {String order=request.contains("A-1")?"A-1":"unknown";response="{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"order:"+order+"\"}],\"isError\":false}}";}byte[] bytes=response.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("mcp-session-id","fake-session");x.sendResponseHeaders(200,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}}
}
