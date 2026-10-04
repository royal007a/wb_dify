package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.knowledge.application.DocumentIndexingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Production upload limits, real Tomcat and both repository nginx templates. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:upload-network;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
@DirtiesContext
class UploadBoundaryNetworkTest {
    static final int MIB=1024*1024;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    // The boundary under test ends after accepting/persisting the file, not embedding a 10MiB corpus.
    @MockBean DocumentIndexingService indexing;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build();

    @Test void directApplicationAcceptsUpToTenMibAndRejectsFileOrRequestOverflow() throws Exception {
        verifyBoundaries("http://127.0.0.1:"+port+"/api/v1",true);
    }

    @ParameterizedTest @ValueSource(strings={"nginx.conf","nginx-path.conf"})
    void bothNginxEntrypointsPreserveUploadAndSafeJsonLimits(String template) throws Exception {
        Testcontainers.exposeHostPorts(port);
        String config=Files.readString(repositoryFile("deploy/"+template));
        String upstream="http://host.testcontainers.internal:"+port;
        if(template.equals("nginx.conf")) config=config.replace("http://hify-app:8080",upstream);
        else config="server { listen 80;\n"+config.replace("http://127.0.0.1:28080",upstream)+"\n}";
        try(var nginx=new GenericContainer<>("nginx:1.27-alpine").withExposedPorts(80)
                .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8)),"/etc/nginx/conf.d/default.conf")
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofSeconds(30))) {
            nginx.start();
            assertThat(nginx.execInContainer("nginx","-t").getExitCode()).isZero();
            String prefix=template.equals("nginx.conf")?"/api/v1":"/hify/api/v1";
            verifyBoundaries("http://"+nginx.getHost()+":"+nginx.getMappedPort(80)+prefix,false);
        }
    }

    private void verifyBoundaries(String api,boolean exactLimit) throws Exception {
        var created=send(api+"/knowledge-bases","application/json",
                json.writeValueAsBytes(java.util.Map.of("name","upload-"+UUID.randomUUID())));
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").asText();
        String endpoint=api+"/knowledge-bases/"+id+"/documents";
        var accepted=upload(endpoint,2*MIB);
        assertThat(accepted.statusCode()).as("2MiB through %s",api).isEqualTo(202);
        String document=json.readTree(accepted.body()).path("data").asText();
        var loaded=client.send(HttpRequest.newBuilder(URI.create(api+"/documents/"+document))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(loaded.statusCode()).isEqualTo(200);
        assertThat(json.readTree(loaded.body()).path("data").path("fileSize").asLong()).isEqualTo(2L*MIB);
        if(exactLimit) assertThat(upload(endpoint,10*MIB).statusCode()).as("10MiB exact file").isEqualTo(202);
        assertRejected(upload(endpoint,10*MIB+1)); // Below request/proxy ceiling; file parser must reject.
        assertRejected(upload(endpoint,13*MIB)); // Over request/proxy ceiling; bounded finite-body test.
    }

    private HttpResponse<String> upload(String uri,int fileSize) throws Exception {
        String boundary="synthetic-upload-boundary";
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.txt\"\r\n"
                +"Content-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body=new byte[prefix.length+fileSize+suffix.length];
        System.arraycopy(prefix,0,body,0,prefix.length);
        java.util.Arrays.fill(body,prefix.length,prefix.length+fileSize,(byte)'a');
        System.arraycopy(suffix,0,body,prefix.length+fileSize,suffix.length);
        return send(uri,"multipart/form-data; boundary="+boundary,body);
    }
    private HttpResponse<String> send(String uri,String type,byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(20))
                .header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void assertRejected(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(json.readTree(response.body()).path("code").asInt()).isEqualTo(41300);
        assertThat(response.body()).doesNotContain("synthetic.txt","synthetic-upload-boundary","<html>","Exception");
    }
    private Path repositoryFile(String relative) {
        Path path=Path.of("").toAbsolutePath();
        while(path!=null){if(Files.isRegularFile(path.resolve(relative)))return path.resolve(relative);path=path.getParent();}
        throw new IllegalStateException("Repository deployment template unavailable");
    }
}
