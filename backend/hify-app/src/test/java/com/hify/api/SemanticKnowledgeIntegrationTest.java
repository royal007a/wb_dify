package com.hify.api;

import com.fasterxml.jackson.databind.*;
import com.hify.common.*;
import com.hify.knowledge.api.*;
import com.hify.provider.api.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:semantic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","hify.provider.allow-private=true",
        "hify.resilience.timeout-max-attempts=1"})
class SemanticKnowledgeIntegrationTest {
    static final AtomicBoolean broken=new AtomicBoolean();
    static final AtomicInteger calls=new AtomicInteger();
    static final ObjectMapper mapper=new ObjectMapper();
    static final HttpServer upstream=start();
    static HttpServer start(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",e->{
            calls.incrementAndGet();var request=mapper.readTree(e.getRequestBody());
            List<Map<String,Object>> data=new ArrayList<>();int i=0;
            for(var input:request.path("input")){
                boolean related=input.asText().contains("automobile")||input.asText().contains("vehicle");
                data.add(Map.of("index",i++,"embedding",broken.get()?List.of(1):related?List.of(1,0,0):List.of(0,1,0)));
            }
            Collections.reverse(data);byte[] body=mapper.writeValueAsBytes(Map.of("data",data));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
        });server.start();return server;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void grants(DynamicPropertyRegistry p){
        System.setProperty("SEMANTIC_FIXTURE_KEY","synthetic-only");
        p.add("hify.credentials.reference-bindings",()->"{\"system:SEMANTIC_FIXTURE_KEY\":[\""+url()+"\"]}");
    }
    static String url(){return "http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1";}
    @Autowired MockMvc http; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @Autowired KnowledgeRetrievalPort retrieval; @Autowired EmbeddingService embeddings;
    @BeforeEach void reset(){broken.set(false);}
    String provider()throws Exception{
        return data(http.perform(post("/api/v1/providers").contentType("application/json").content(json.writeValueAsString(Map.of(
                "name","semantic-"+UUID.randomUUID(),"type","OPENAI_COMPATIBLE","baseUrl",url(),
                "auth",Map.of("credentialRef","system:SEMANTIC_FIXTURE_KEY"),"models",List.of(Map.of("displayName","embed","modelId","embed","enabled",true,"isDefault",true))))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).asText();
    }
    String base(String provider)throws Exception{
        return data(http.perform(post("/api/v1/knowledge-bases").contentType("application/json").content(json.writeValueAsString(Map.of(
                "name","semantic-"+UUID.randomUUID(),"chunkSize",128,"chunkOverlap",8,"embedding",Map.of("providerId",provider,"model","embed","dimensions",3)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).asText();
    }
    String upload(String base,String text)throws Exception{
        String id=data(http.perform(multipart("/api/v1/knowledge-bases/{id}/documents",base).file(new MockMultipartFile("file","test.txt","text/plain",text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).asText();
        for(int i=0;i<100;i++){
            String state=db.queryForObject("select indexing_state from knowledge_documents where id=?",String.class,id);
            if(state.equals("DONE")||state.equals("FAILED"))return id;Thread.sleep(50);
        }throw new AssertionError("index not terminal");
    }
    JsonNode data(String body)throws Exception{return json.readTree(body).path("data");}
    @Test void semanticOnlyMatchAndFrozenVectorTampering()throws Exception{
        String base=base(provider());String doc=upload(base,"automobile servicing");upload(base,"orchard irrigation");
        assertThat(db.queryForObject("select indexing_state from knowledge_documents where id=?",String.class,doc)).isEqualTo("DONE");
        assertThat(retrieval.search(base,"vehicle maintenance",1)).extracting(KnowledgeCitation::documentId).containsExactly(doc);
        var frozen=retrieval.freeze(base);
        http.perform(delete("/api/v1/documents/{id}",doc)).andExpect(status().isOk());
        assertThat(retrieval.searchSnapshot(frozen,"vehicle maintenance",1)).extracting(KnowledgeCitation::documentId).containsExactly(doc);
        db.update("update document_chunks set semantic_vector='[0,1,0]' where document_id=?",doc);
        assertThatThrownBy(()->retrieval.searchSnapshot(frozen,"vehicle maintenance",1)).isInstanceOf(BizException.class).hasMessageContaining("摘要");
    }
    @Test void malformedVectorsFailIndexWithoutHashFallback()throws Exception{
        String base=base(provider());broken.set(true);String doc=upload(base,"automobile servicing");
        assertThat(db.queryForObject("select indexing_state from knowledge_documents where id=?",String.class,doc)).isEqualTo("FAILED");
        assertThat(db.queryForObject("select count(*) from document_chunks where document_id=?",Integer.class,doc)).isZero();
    }
    @Test void fixedProfileReadbackAndUpdateRefusal()throws Exception{
        String p=provider(),base=base(p);
        var view=data(http.perform(get("/api/v1/knowledge-bases/{id}",base)).andReturn().getResponse().getContentAsString());
        assertThat(view.path("embedding").path("model").asText()).isEqualTo("embed");
        assertThat(view.toString()).doesNotContain("synthetic-only","authConfig");
        http.perform(put("/api/v1/knowledge-bases/{id}",base).contentType("application/json").content(json.writeValueAsString(Map.of("name",view.path("name").asText(),"embedding",Map.of("providerId",p,"model","other","dimensions",3)))))
                .andExpect(status().isConflict());
    }
    @Test void reorderedBatchAndCancellationBeforeNetwork()throws Exception{
        var profile=embeddings.freeze(provider(),"embed",3);
        var out=embeddings.embed(profile,List.of("automobile","orchard"),ExecutionControl.none());
        assertThat(out.get(0)).containsExactly(1,0,0);assertThat(out.get(1)).containsExactly(0,1,0);
        int before=calls.get();
        assertThatThrownBy(()->embeddings.embed(profile,List.of("automobile"),ExecutionControl.withTimeout(java.time.Duration.ofSeconds(2),()->true))).isInstanceOf(ExecutionCancelledException.class);
        assertThat(calls.get()).isEqualTo(before);
    }
}
