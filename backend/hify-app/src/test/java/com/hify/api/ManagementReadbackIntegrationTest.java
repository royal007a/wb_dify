package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.agent.api.AgentQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @DirtiesContext
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:management-readback;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
class ManagementReadbackIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired AgentQueryService agents;

    @Test void knowledgeCrudAndDocumentPaginationHavePositiveAndArchivedControls() throws Exception {
        String name="kb-"+UUID.randomUUID();
        String id=call(post("/api/v1/knowledge-bases").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("name",name,"chunkSize",128,"chunkOverlap",16))),201)
                .path("data").asText();
        JsonNode listing=call(get("/api/v1/knowledge-bases").param("keyword",name).param("page","1").param("pageSize","1"),200);
        assertThat(listing.path("data")).hasSize(1);
        assertThat(listing.path("data").get(0).path("id").asText()).isEqualTo(id);
        assertThat(listing.path("total").asInt()).isEqualTo(1);
        assertThat(listing.path("size").asInt()).isEqualTo(1);
        call(get("/api/v1/knowledge-bases").param("page","0"),400);
        assertThat(call(get("/api/v1/knowledge-bases/{id}/documents",id),200).path("data")).isEmpty();

        var file=new MockMultipartFile("file","readback.md","text/markdown",
                "# 售后\n七天内未拆封可以退货。".getBytes(StandardCharsets.UTF_8));
        String documentId=call(multipart("/api/v1/knowledge-bases/{id}/documents",id).file(file),202).path("data").asText();
        Instant deadline=Instant.now().plus(Duration.ofSeconds(5));
        JsonNode document;
        do {
            document=call(get("/api/v1/documents/{id}",documentId),200).path("data");
            if(document.path("indexingState").asText().matches("DONE|FAILED")) break;
            Thread.sleep(25);
        } while(Instant.now().isBefore(deadline));
        assertThat(document.path("indexingState").asText()).isEqualTo("DONE");
        JsonNode documents=call(get("/api/v1/knowledge-bases/{id}/documents",id).param("pageSize","1"),200);
        assertThat(documents.path("total").asInt()).isEqualTo(1);
        assertThat(documents.path("data").get(0).path("id").asText()).isEqualTo(documentId);

        call(put("/api/v1/knowledge-bases/{id}",id).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("name",name,"description","updated","chunkSize",256,"chunkOverlap",32,"enabled",false))),200);
        JsonNode updated=call(get("/api/v1/knowledge-bases/{id}",id),200).path("data");
        assertThat(updated.path("description").asText()).isEqualTo("updated");
        assertThat(updated.path("chunkSize").asInt()).isEqualTo(256);
        assertThat(updated.path("chunkOverlap").asInt()).isEqualTo(32);
        assertThat(updated.path("enabled").asBoolean()).isFalse();
        call(multipart("/api/v1/knowledge-bases/{id}/documents",id).file(file),409);
        call(delete("/api/v1/knowledge-bases/{id}",id),200);
        call(get("/api/v1/knowledge-bases/{id}",id),404);
        call(get("/api/v1/documents/{id}",documentId),404);
        assertThat(call(get("/api/v1/knowledge-bases").param("keyword",name),200).path("data")).isEmpty();
    }

    @Test void workflowReadbackAndArchiveRetainTheExactPublishedVersion() throws Exception {
        String workflow=createWorkflow();
        JsonNode version=publishWorkflow(workflow);
        String versionId=version.path("id").asText();
        JsonNode listing=call(get("/api/v1/workflows").param("pageSize","100"),200).path("data");
        assertThat(listing.findValuesAsText("id")).contains(workflow);
        JsonNode clamped=call(get("/api/v1/workflows").param("page","0").param("pageSize","101"),200);
        assertThat(clamped.path("page").asInt()).isEqualTo(1);
        assertThat(clamped.path("size").asInt()).isEqualTo(100);
        JsonNode versions=call(get("/api/v1/workflows/{id}/versions",workflow),200).path("data");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).isEqualTo(version);
        JsonNode detail=call(get("/api/v1/workflow-versions/{id}",versionId),200).path("data");
        assertThat(detail.path("checksum")).isEqualTo(version.path("checksum"));
        assertThat(detail.path("nodes")).hasSize(2);
        JsonNode run=call(post("/api/v1/workflow-versions/{id}/runs",versionId)
                .contentType("application/json").content("{\"input\":\"hello\"}"),202).path("data");
        assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("output").asText()).isEqualTo("published-answer");
        assertThat(call(get("/api/v1/workflow-runs/{id}",run.path("id").asText()),200).path("data")).isEqualTo(run);
        call(delete("/api/v1/workflows/{id}",workflow),200);
        call(get("/api/v1/workflows/{id}",workflow),404);
        assertThat(call(get("/api/v1/workflows").param("pageSize","100"),200).path("data").findValuesAsText("id")).doesNotContain(workflow);
        assertThat(call(get("/api/v1/workflow-versions/{id}",versionId),200).path("data")).isEqualTo(detail);
    }

    @Test void agentWorkflowUnbindChangesOnlyDraftAndNewPublication() throws Exception {
        String workflow=createWorkflow();
        JsonNode publishedWorkflow=publishWorkflow(workflow);
        String workflowVersion=publishedWorkflow.path("id").asText();
        String workflowChecksum=publishedWorkflow.path("checksum").asText();
        assertThat(workflowChecksum).hasSize(64);
        String agent=call(post("/api/v1/agents").contentType("application/json").content("""
                {"name":"unbind-%s","instructions":"test","providerId":"mock","modelId":"hify-mock",
                 "temperature":0.2,"maxTokens":2048,"maxTurns":6,"maxContextTurns":10,"enabledTools":[],"enabled":true}
                """.formatted(UUID.randomUUID())),201).path("data").asText();
        call(put("/api/v1/agents/{id}/workflow-binding",agent).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("workflowId",workflow))),200);
        String oldVersion=call(post("/api/v1/agents/{id}/publications",agent),200).path("data").path("id").asText();
        JsonNode oldConversation=call(post("/api/v1/conversations").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("agentId",agent))),201);
        assertThat(oldConversation.path("agentVersionId").asText()).isEqualTo(oldVersion);
        assertThat(call(get("/api/v1/agents/{id}",agent),200).path("data").path("workflowBinding").path("workflowId").asText()).isEqualTo(workflow);
        assertThat(agents.requireVersion(oldVersion).workflowBinding().workflowVersionId()).isEqualTo(workflowVersion);
        call(delete("/api/v1/agents/{id}/workflow-binding",agent),200);
        assertThat(call(get("/api/v1/agents/{id}",agent),200).path("data").path("workflowBinding").isNull()).isTrue();
        assertThat(agents.requireVersion(oldVersion).workflowBinding().workflowVersionId()).isEqualTo(workflowVersion);
        String newVersion=call(post("/api/v1/agents/{id}/publications",agent),200).path("data").path("id").asText();
        assertThat(agents.requireVersion(newVersion).workflowBinding()).isNull();
        assertThat(agents.requireVersion(oldVersion).workflowBinding().workflowVersionId()).isEqualTo(workflowVersion);
        assertThat(agents.requireVersion(oldVersion).workflowBinding().checksum()).isEqualTo(workflowChecksum);

        String runId=call(post("/api/v1/conversations/{id}/runs",oldConversation.path("id").asText())
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"message\":\"hello after unbind\"}"),202).path("id").asText();
        JsonNode result=awaitRun(runId);
        assertThat(result.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("agentVersionId").asText()).isEqualTo(oldVersion).isNotEqualTo(newVersion);
        assertThat(result.path("outputMessage").asText()).isEqualTo("published-answer");
        JsonNode runEvents=call(get("/api/v1/runs/{id}/events",runId),200);
        JsonNode started=null,completed=null;
        for(JsonNode event:runEvents) {
            if(event.path("type").asText().equals("workflow.started")) started=json.readTree(event.path("payload").asText());
            if(event.path("type").asText().equals("workflow.completed")) completed=json.readTree(event.path("payload").asText());
        }
        assertThat(started).isNotNull();
        assertThat(completed).isNotNull();
        assertThat(started.path("workflowVersionId").asText()).isEqualTo(workflowVersion);
        assertThat(started.path("workflowChecksum").asText()).isEqualTo(workflowChecksum);
        JsonNode execution=call(get("/api/v1/workflow-runs/{id}",completed.path("workflowRunId").asText()),200).path("data");
        assertThat(execution.path("workflowVersionId").asText()).isEqualTo(workflowVersion);
        assertThat(execution.path("workflowDigest").asText()).isEqualTo(workflowChecksum);
        assertThat(execution.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(execution.path("output").asText()).isEqualTo("published-answer");
    }

    @Test void legacyConversationAndToolCatalogReturnActualFields() throws Exception {
        JsonNode conversation=call(post("/api/v1/conversations").contentType("application/json")
                .content("{\"agentId\":\"demo-agent\",\"title\":\"readback\"}"),201);
        JsonNode view=call(get("/api/conversations/{id}",conversation.path("id").asText()),200);
        assertThat(view.path("conversation").path("id")).isEqualTo(conversation.path("id"));
        assertThat(view.path("conversation").path("title").asText()).isEqualTo("readback");
        assertThat(view.path("messages")).isEmpty();
        JsonNode tools=call(get("/api/tools"),200);
        assertThat(tools.findValuesAsText("name")).containsExactlyInAnyOrder("current_time","calculator");
        for(JsonNode tool:tools) assertThat(tool.path("inputSchema").isObject()).isTrue();
    }

    private String createWorkflow() throws Exception {
        return call(post("/api/v1/workflows").contentType("application/json").content("""
                {"name":"readback-%s","schemaVersion":1,
                 "nodes":[{"nodeKey":"start","type":"START","name":"Start","config":{}},
                          {"nodeKey":"end","type":"END","name":"End","config":{"output":"published-answer"}}],
                 "edges":[{"edgeKey":"e1","sourceNodeKey":"start","targetNodeKey":"end","defaultBranch":false}]}
                """.formatted(UUID.randomUUID())),201).path("data").asText();
    }
    private JsonNode publishWorkflow(String id) throws Exception { return call(post("/api/v1/workflows/{id}/versions",id),200).path("data"); }
    private JsonNode awaitRun(String id) throws Exception {
        Instant deadline=Instant.now().plusSeconds(5);
        JsonNode result;
        do {
            result=call(get("/api/v1/runs/{id}",id),200);
            if(!result.path("state").asText().equals("RUNNING")) return result;
            Thread.sleep(20);
        } while(Instant.now().isBefore(deadline));
        throw new AssertionError("Run did not finish: "+id);
    }
    private JsonNode call(MockHttpServletRequestBuilder request,int status) throws Exception {
        return json.readTree(http.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
}
