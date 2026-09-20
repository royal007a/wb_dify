package com.hify.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties={
        "spring.datasource.url=jdbc:h2:mem:hify-knowledge-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password="
})
class KnowledgeApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired KnowledgeRetrievalPort retrieval;

    @Test
    void uploadsIndexesRetrievesAndArchivesCanonicalEvidence() throws Exception {
        JsonNode created=body(http.perform(post("/api/v1/knowledge-bases")
                .contentType("application/json").content("""
                {"name":"售后手册","description":"退换货政策","chunkSize":64,"chunkOverlap":8}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String baseId=created.path("data").asText();
        MockMultipartFile file=new MockMultipartFile("file","policy.md","text/markdown",("""
                # 退货政策

                商品自签收之日起七天内，未拆封且不影响二次销售，可以无理由退货。

                # 质量问题

                商品存在质量问题时，三十天内可以申请换货。退款审核通常需要三个工作日。
                """).getBytes(StandardCharsets.UTF_8));
        JsonNode uploaded=body(http.perform(multipart("/api/v1/knowledge-bases/{id}/documents",baseId).file(file))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        String documentId=uploaded.path("data").asText();
        JsonNode document=awaitDocument(documentId);
        assertThat(document.path("indexingState").asText()).isEqualTo("DONE");
        assertThat(document.path("chunkCount").asInt()).isGreaterThan(0);

        JsonNode chunks=body(http.perform(get("/api/v1/documents/{id}/chunks",documentId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(chunks).isNotEmpty();
        String chunkId=chunks.get(0).path("chunkId").asText(); String digest=chunks.get(0).path("digest").asText();
        assertThat(retrieval.requireCanonicalChunk(chunkId,digest).content()).contains("退货政策");
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(chunkId,"stale-digest")).isInstanceOf(BizException.class);

        JsonNode results=body(http.perform(post("/api/v1/knowledge-bases/{id}/retrieval-tests",baseId)
                .contentType("application/json").content("{\"query\":\"七天无理由退货\",\"topK\":3}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).path("content").asText()).contains("七天");
        assertThat(results.get(0).path("digest").asText()).isNotBlank();

        http.perform(delete("/api/v1/documents/{id}",documentId)).andExpect(status().isOk());
        http.perform(get("/api/v1/documents/{id}",documentId)).andExpect(status().isNotFound());
        assertThatThrownBy(()->retrieval.requireCanonicalChunk(chunkId,digest)).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsUnsupportedFilesAndInvalidChunkPolicy() throws Exception {
        http.perform(post("/api/v1/knowledge-bases").contentType("application/json")
                .content("{\"name\":\"坏配置\",\"chunkSize\":64,\"chunkOverlap\":64}"))
                .andExpect(status().isBadRequest());
        String id=body(http.perform(post("/api/v1/knowledge-bases").contentType("application/json")
                .content("{\"name\":\"文件边界\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("data").asText();
        MockMultipartFile pdf=new MockMultipartFile("file","scan.pdf","application/pdf",new byte[]{1,2,3});
        http.perform(multipart("/api/v1/knowledge-bases/{id}/documents",id).file(pdf))
                .andExpect(status().isBadRequest());
    }

    private JsonNode awaitDocument(String id) throws Exception {
        Instant deadline=Instant.now().plus(Duration.ofSeconds(5));
        while(Instant.now().isBefore(deadline)){
            JsonNode value=body(http.perform(get("/api/v1/documents/{id}",id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");
            if(value.path("indexingState").asText().matches("DONE|FAILED"))return value;
            Thread.sleep(25);
        }
        throw new AssertionError("document indexing did not finish");
    }
    private JsonNode body(String value)throws Exception{return json.readTree(value);}
}
