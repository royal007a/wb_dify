package com.hify.api;

import com.hify.application.RunEventBroker;
import com.hify.domain.*;
import com.hify.infra.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:sse-network;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=",
        "server.tomcat.connection-timeout=1s","hify.sse.max-subscribers=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SseNetworkIsolationTest {
    @LocalServerPort int port;
    @Autowired RunEventBroker broker;
    @Autowired AgentRunRepository runs;
    @Autowired ConversationRepository conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired HikariDataSource pool;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build();

    @Test void nonReadingSocketCannotHoldTransactionsOrAnotherRunAndIsTimedOut() throws Exception {
        AgentRun slow=fixture(),fast=fixture();
        List<Object[]> batch=new ArrayList<>();
        String payload="{\"delta\":\""+"x".repeat(16000)+"\"}";
        for(int i=1;i<=512;i++)batch.add(new Object[]{slow.getId(),i,"message.delta",payload,java.sql.Timestamp.from(Instant.now())});
        jdbc.batchUpdate("insert into run_events(run_id,sequence_no,event_type,payload,created_at) values(?,?,?,?,?)",batch);
        ExecutorService callers=Executors.newSingleThreadExecutor();
        try(Socket socket=unread(slow.getId())) {
            // Observe an actual Tomcat blocking write, not merely a slow mock callback.
            await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5)).until(SseNetworkIsolationTest::hasBlockedSocketWriter);
            await().atMost(Duration.ofSeconds(1)).untilAsserted(()->assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero());
            long started=System.nanoTime();
            callers.submit(()->broker.publish(slow.getId(),"message.delta",Map.of("delta","post-block-commit"))).get(1,TimeUnit.SECONDS);
            long commitMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            broker.publish(fast.getId(),"run.completed",Map.of("state","COMPLETED"));
            HttpResponse<String> response=read(fast.getId(),null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("event:run.completed");
            await().atMost(Duration.ofSeconds(8)).until(()->!subscribed(slow.getId()));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero());
            broker.publish(slow.getId(),"run.completed",Map.of("state","COMPLETED"));
            long cursor=jdbc.queryForObject("select id from run_events where run_id=? and sequence_no=510",Long.class,slow.getId());
            var replay=read(slow.getId(),cursor);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body().split("event:message.delta",-1)).hasSize(4); // 511, 512, post-block
            assertThat(replay.body()).contains("event:run.completed","post-block-commit");
            System.out.println("SSE_SOCKET_ISOLATION commitMs="+commitMs+" blockedWriteObserved=true replayDeltas=3");
        } finally {callers.shutdownNow();}
    }

    @Test void foreignAndUnknownCursorReturnJson400AndZeroCursorReplays() throws Exception {
        AgentRun one=fixture(),two=fixture();
        long other=broker.publish(two.getId(),"run.completed",Map.of()).getId();
        broker.publish(one.getId(),"run.completed",Map.of());
        for(long cursor:new long[]{other,Long.MAX_VALUE,-1}) {
            var response=read(one.getId(),cursor);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
            assertThat(response.body()).contains("40000").doesNotContain("event:run.completed");
        }
        assertThat(read(one.getId(),0L).body()).contains("event:run.completed");
    }

    @Test void subscriptionLimitFailsFastAndDoesNotQueueMoreWorkers() throws Exception {
        AgentRun a=fixture(),b=fixture(),c=fixture();
        try(Socket first=unread(a.getId());Socket second=unread(b.getId())) {
            await().atMost(Duration.ofSeconds(3)).until(()->subscribed(a.getId())&&subscribed(b.getId()));
            var response=read(c.getId(),null);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("50300").doesNotContain("ThreadPoolExecutor");
            var workers=(ThreadPoolExecutor)ReflectionTestUtils.getField(broker,"senders");
            assertThat(workers.getActiveCount()).isEqualTo(2);
            assertThat(workers.getQueue()).isEmpty();
            broker.publish(a.getId(),"run.completed",Map.of());
            broker.publish(b.getId(),"run.completed",Map.of());
            await().atMost(Duration.ofSeconds(3)).until(()->!subscribed(a.getId())&&!subscribed(b.getId()));
        }
    }

    AgentRun fixture(){String id=UUID.randomUUID().toString();conversations.saveAndFlush(new Conversation(id,"demo-agent","SSE test",Instant.now()));return runs.saveAndFlush(new AgentRun("run-"+id,id,"key","hash","input",Instant.now()));}
    Socket unread(String runId) throws Exception {Socket socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",port),2000);socket.getOutputStream().write(("GET /api/v1/runs/"+runId+"/events/stream HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();return socket;}
    HttpResponse<String> read(String runId,Long cursor) throws Exception {var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/runs/"+runId+"/events/stream")).timeout(Duration.ofSeconds(5)).header("Accept","text/event-stream");if(cursor!=null)builder.header("Last-Event-ID",cursor.toString());return client.send(builder.GET().build(),HttpResponse.BodyHandlers.ofString());}
    boolean subscribed(String runId){return ((Map<?,?>)ReflectionTestUtils.getField(broker,"subscribers")).containsKey(runId);}
    static boolean hasBlockedSocketWriter(){return Thread.getAllStackTraces().entrySet().stream().anyMatch(e->e.getKey().getName().startsWith("hify-sse-")&&Arrays.stream(e.getValue()).anyMatch(frame->frame.getClassName().contains("NioEndpoint$NioSocketWrapper")&&frame.getMethodName().equals("doWrite")));}
}
