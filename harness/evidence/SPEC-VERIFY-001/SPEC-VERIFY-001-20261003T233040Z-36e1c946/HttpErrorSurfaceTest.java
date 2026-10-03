package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Pending red fixture: restore under hify-app/src/test/java/com/hify/api before the repair. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:http-error-surface;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=4KB"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class HttpErrorSurfaceTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build();
    static final String MARKER="sensitive-test-marker-456";

    @Test void frameworkErrorsHaveExactSafeJsonContracts() throws Exception {
        check(send("GET","/api/v1/providers?page="+MARKER,null,null),400,40000);
        check(send("POST","/api/v1/conversations","application/json","{\"agentId\":\""+MARKER),400,40000);
        check(send("PUT","/api/v1/health",null,null),405,40500);
        check(send("POST","/api/v1/conversations","text/plain",MARKER),415,41500);
        check(send("GET","/api/v1/conversations/missing/runs/by-key",null,null),400,40000);
    }

    @Test void unknownRouteDoesNotReflectItsPath() throws Exception {
        check(send("GET","/api/v1/unknown-"+MARKER,null,null),404,40400);
    }

    @Test void missingRunAndEventsReturnSafeNotFound() throws Exception {
        check(send("GET","/api/v1/runs/"+MARKER,null,null),404,40400);
        check(send("GET","/api/v1/runs/"+MARKER+"/events",null,null),404,40400);
    }

    @Test void multipartParserRejectsOversizeWith413() throws Exception {
        String boundary="hify-synthetic-boundary";
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+MARKER+".txt\"\r\n"
                +"Content-Type: text/plain\r\n\r\n"+"x".repeat(2048)+"\r\n--"+boundary+"--\r\n";
        check(send("POST","/api/v1/knowledge-bases/missing/documents","multipart/form-data; boundary="+boundary,body),413,41300);
    }

    @Test void malformedMultipartReturnsSafe400() throws Exception {
        check(send("POST","/api/v1/knowledge-bases/missing/documents","multipart/form-data",MARKER),400,40000);
    }

    private HttpResponse<String> send(String method,String path,String type,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5));
        if(type!=null)request.header("Content-Type",type);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void check(HttpResponse<String> response,int status,int code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(MARKER,"ThreadPoolExecutor","java.lang.","org.springframework");
    }
}
