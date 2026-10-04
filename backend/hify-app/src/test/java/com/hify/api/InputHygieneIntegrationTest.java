package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.knowledge.application.KnowledgeApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:input-hygiene;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "hify.provider.allow-private=true", "hify.mcp.allow-private=true",
        "hify.credentials.reference-bindings={\"env:HYGIENE_TEST_TOKEN\":[\"http://127.0.0.1:19099/v1\"]}"})
@AutoConfigureMockMvc @DirtiesContext
class InputHygieneIntegrationTest {
    static final String NUL=String.valueOf((char)0);
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired KnowledgeApplicationService knowledge;
    @Autowired com.hify.agent.api.AgentService agents;
    @Autowired com.hify.workflow.application.WorkflowApplicationService workflows;
    @Autowired com.hify.provider.api.ProviderService providers;
    @Autowired com.hify.mcp.application.McpRegistryService mcp;

    @Test void uploadRejectsContentAndMetadataBeforeDocumentsOrIndexTasksExist() throws Exception {
        String id=call(post("/api/v1/knowledge-bases").contentType("application/json")
                .content("{\"name\":\"upload-"+UUID.randomUUID()+"\"}"),201).path("data").asText();
        long tasks=count("document_index_tasks");
        for(var file:List.of(file("a.txt","text/plain","a"+NUL+"b"),
                file("a"+NUL+".txt","text/plain","valid"),
                file("a.txt","text/plain"+NUL,"valid"))) {
            call(multipart("/api/v1/knowledge-bases/{id}/documents",id).file(file),400);
            assertThatThrownBy(()->knowledge.upload(id,file)).isInstanceOfSatisfying(BizException.class,
                    error->assertThat(error.errorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
            assertThat(db.queryForObject("select count(*) from knowledge_documents where knowledge_base_id=?",Long.class,id)).isZero();
            assertThat(count("document_index_tasks")).isEqualTo(tasks);
        }
        String text="中文🙂\n literal \\u0000 is ordinary text";
        String doc=call(multipart("/api/v1/knowledge-bases/{id}/documents",id)
                .file(file("正常.txt","text/plain",text)),202).path("data").asText();
        assertThat(db.queryForObject("select canonical_content from knowledge_documents where id=?",String.class,doc)).isEqualTo(text);
        assertThat(db.queryForObject("select count(*) from document_index_tasks where document_id=?",Long.class,doc)).isEqualTo(1);
    }

    @Test void knowledgeTextCreateAndUpdateRejectWithoutChangingRows() throws Exception {
        checkCrud("knowledge-bases","knowledge_bases",tree("{\"name\":\"KB\",\"description\":\"中文🙂\"}"),List.of("/name","/description"));
    }

    @Test void agentTextCreateAndUpdateRejectWithoutChangingRows() throws Exception {
        checkCrud("agents","agent_definitions",tree("""
                {"name":"agent","description":"中文🙂","instructions":"test","providerId":"mock","modelId":"hify-mock",
                 "temperature":0.2,"maxTokens":1024,"maxTurns":3,"maxContextTurns":3,"enabledTools":[],"enabled":true}
                """),List.of("/name","/description","/instructions","/providerId","/modelId"));
    }

    @Test void workflowNestedTextCreateAndUpdateRejectWithoutChangingGraph() throws Exception {
        checkCrud("workflows","workflows",workflow(),List.of("/name","/description","/nodes/0/name","/nodes/0/nodeKey",
                "/nodes/0/type","/nodes/1/config/output","/edges/0/edgeKey","/edges/0/sourceNodeKey",
                "/edges/0/targetNodeKey","/edges/0/condition"));
        ObjectNode bad=workflow(); ((ObjectNode)bad.at("/nodes/1/config")).put("bad"+NUL,"value");
        long before=count("workflows");
        call(post("/api/v1/workflows").contentType("application/json").content(bad.toString()),400);
        assertThatThrownBy(()->workflows.create(json.treeToValue(bad,com.hify.workflow.api.WorkflowDraftRequest.class)))
                .isInstanceOf(BizException.class);
        assertThat(count("workflows")).isEqualTo(before);
    }

    @Test void providerAndModelTextCreateAndUpdateRejectBeforeReplacingModels() throws Exception {
        checkCrud("providers","providers",tree("""
                {"name":"provider","type":"OPENAI_COMPATIBLE","baseUrl":"http://127.0.0.1:19099/v1",
                 "enabled":true,"auth":{"credentialRef":"env:HYGIENE_TEST_TOKEN","headerName":"Authorization","prefix":"Bearer"},
                 "models":[{"displayName":"中文🙂","modelId":"test-model","enabled":true,"isDefault":true}]}
                """),List.of("/name","/baseUrl","/auth/credentialRef","/auth/headerName","/auth/prefix",
                "/models/0/displayName","/models/0/modelId"));
    }

    @Test void mcpTextCreateAndUpdateRejectBeforeSavingServerOrCredential() throws Exception {
        checkCrud("mcp-servers","mcp_servers",tree("""
                {"name":"mcp","endpointUrl":"http://127.0.0.1:19099/mcp","enabled":true,"credentialAction":"CLEAR"}
                """),List.of("/name","/endpointUrl","/credentialRef","/credentialAction","/credentialToken"));
    }

    @Test void conversationTitleIsRejectedAndPlainBackslashTextIsPreserved() throws Exception {
        long before=count("conversations");
        for(String value:List.of(NUL+"title","title"+NUL,"ti"+NUL+"tle")) {
            var body=json.createObjectNode().put("agentId","demo-agent").put("title",value);
            call(post("/api/v1/conversations").contentType("application/json").content(body.toString()),400);
            assertThat(count("conversations")).isEqualTo(before);
        }
        String text="中文🙂 literal \\u0000";
        JsonNode created=call(post("/api/v1/conversations").contentType("application/json")
                .content(json.createObjectNode().put("agentId","demo-agent").put("title",text).toString()),201);
        assertThat(created.path("title").asText()).isEqualTo(text);
    }

    private void checkCrud(String resource,String table,ObjectNode good,List<String> paths) throws Exception {
        good.put("name",good.path("name").asText()+"-"+UUID.randomUUID());
        String url="/api/v1/"+resource;
        String id=call(post(url).contentType("application/json").content(good.toString()),201).path("data").asText();
        long before=count(table);
        JsonNode original=call(get(url+"/"+id),200);
        for(String path:paths) {
            ObjectNode bad=good.deepCopy();
            int slash=path.lastIndexOf('/');
            ((ObjectNode)bad.at(path.substring(0,slash))).put(path.substring(slash+1),"invalid"+NUL+"tail");
            call(post(url).contentType("application/json").content(bad.toString()),400);
            call(put(url+"/"+id).contentType("application/json").content(bad.toString()),400);
            // Direct application calls: protection must not rely solely on MVC validation.
            assertThatThrownBy(()->direct(resource,null,bad)).isInstanceOfSatisfying(BizException.class,
                    e->assertThat(e.errorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
            assertThatThrownBy(()->direct(resource,id,bad)).isInstanceOfSatisfying(BizException.class,
                    e->assertThat(e.errorCode()).isEqualTo(ErrorCode.PARAM_ERROR));
            assertThat(count(table)).isEqualTo(before);
            assertThat(call(get(url+"/"+id),200)).isEqualTo(original);
        }
        good.put("name","更新🙂-"+UUID.randomUUID());
        call(put(url+"/"+id).contentType("application/json").content(good.toString()),200);
        assertThat(call(get(url+"/"+id),200).path("data").path("name").asText()).isEqualTo(good.path("name").asText());
    }

    private void direct(String resource,String id,ObjectNode body) throws Exception {
        switch(resource) {
            case "knowledge-bases" -> {var r=json.treeToValue(body,com.hify.knowledge.api.KnowledgeBaseRequest.class);if(id==null)knowledge.create(r);else knowledge.update(id,r);}
            case "agents" -> {if(id==null)agents.create(json.treeToValue(body,com.hify.agent.api.AgentUpsertRequest.class));else agents.update(id,json.treeToValue(body,com.hify.agent.api.AgentUpdateRequest.class));}
            case "workflows" -> {var r=json.treeToValue(body,com.hify.workflow.api.WorkflowDraftRequest.class);if(id==null)workflows.create(r);else workflows.update(id,r);}
            case "providers" -> {if(id==null)providers.create(json.treeToValue(body,com.hify.provider.api.ProviderCreateRequest.class));else providers.update(id,json.treeToValue(body,com.hify.provider.api.ProviderUpdateRequest.class));}
            case "mcp-servers" -> {var r=json.treeToValue(body,com.hify.mcp.api.McpServerRequest.class);if(id==null)mcp.create(r);else mcp.update(id,r);}
            default -> throw new AssertionError(resource);
        }
    }

    private ObjectNode workflow() throws Exception {return tree("""
            {"name":"flow","description":"中文🙂","schemaVersion":1,
             "nodes":[{"nodeKey":"start","type":"START","name":"Start","config":{}},
                      {"nodeKey":"end","type":"END","name":"End","config":{"output":"ok"}}],
             "edges":[{"edgeKey":"e1","sourceNodeKey":"start","targetNodeKey":"end","defaultBranch":false}]}
            """);}
    private MockMultipartFile file(String name,String media,String content){return new MockMultipartFile("file",name,media,content.getBytes(StandardCharsets.UTF_8));}
    private ObjectNode tree(String text)throws Exception{return (ObjectNode)json.readTree(text);}
    private long count(String table){return db.queryForObject("select count(*) from "+table,Long.class);}
    private JsonNode call(MockHttpServletRequestBuilder request,int expected)throws Exception {
        var result=http.perform(request).andExpect(status().is(expected));
        if(expected==400)result.andExpect(jsonPath("$.code").value(40000));
        JsonNode value=json.readTree(result.andReturn().getResponse().getContentAsString());
        if(expected==400)assertThat(value.path("message").asText()).doesNotContain("invalid",NUL);
        return value;
    }
}
