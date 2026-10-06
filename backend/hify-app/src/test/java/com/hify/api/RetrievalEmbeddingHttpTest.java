package com.hify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import com.hify.knowledge.application.SemanticEmbeddings;
import com.hify.provider.api.*;
import com.hify.provider.application.*;
import com.hify.provider.runtime.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local HTTP + production embedding/breaker/client; no Spring, database, or real model. */
class RetrievalEmbeddingHttpTest {
    @Test void timelyEmbeddingIsAPositiveControl() throws Exception {
        try(var f=new Fixture(false)) {
            f.release.countDown();
            var vectors=f.semantic.embed(f.profile,List.of("question"),
                    ExecutionControl.withTimeout(Duration.ofSeconds(60),()->false));
            assertThat(vectors).hasSize(1);
            assertThat(vectors.get(0)).containsExactly(1,0);
            assertThat(f.requests).hasValue(1);
        }
    }

    @Test void alreadyCancelledMakesZeroHttpRequests() throws Exception {
        try(var f=new Fixture(false)) {
            assertThatThrownBy(()->f.semantic.embed(f.profile,List.of("question"),
                    ExecutionControl.withTimeout(Duration.ofSeconds(60),()->true)))
                    .isInstanceOf(ExecutionCancelledException.class);
            assertThat(f.requests).hasValue(0);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationDuringBodyOrConnectionFailureDoesNotRetry(boolean resetConnection) throws Exception {
        try(var f=new Fixture(resetConnection)) {
            var cancelled=new AtomicBoolean();
            var control=ExecutionControl.withTimeout(Duration.ofSeconds(60),cancelled::get);
            Future<List<float[]>> result=f.caller.submit(()->f.semantic.embed(f.profile,List.of("question"),control));
            assertThat(f.entered.await(60,TimeUnit.SECONDS)).as("fixture admitted request").isTrue();
            cancelled.set(true);
            if(resetConnection)f.release.countDown();
            // In the body case the fixture is still holding the suffix: completion must
            // come from shared control, not successful receipt of the whole response.
            assertThatThrownBy(()->result.get(30,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ExecutionCancelledException.class);
            assertThat(f.requests).hasValue(1);
            assertThat(f.registry.circuitBreaker("fixture").getMetrics().getNumberOfFailedCalls()).isZero();
        }
    }

    @Test void parentDeadlineCutsOffBodyWithoutWaitingForFortyFiveSecondChildLimit() throws Exception {
        try(var f=new Fixture(false)) {
            // Initialize the production HTTP path before starting a short control window.
            f.warmup();
            var control=ExecutionControl.withTimeout(Duration.ofSeconds(5),()->false);
            Future<List<float[]>> result=f.caller.submit(()->f.semantic.embed(f.profile,List.of("question"),control));
            assertThat(f.entered.await(30,TimeUnit.SECONDS)).as("fixture admitted bounded request").isTrue();
            assertThatThrownBy(()->result.get(30,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ExecutionTimedOutException.class);
            assertThat(control.isExpired()).isTrue();
            assertThat(f.requests).hasValue(1);
            assertThat(f.registry.circuitBreaker("fixture").getMetrics().getNumberOfFailedCalls()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final byte[] BODY="{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(StandardCharsets.UTF_8);
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger requests=new AtomicInteger();
        final ExecutorService serverWorker=Executors.newSingleThreadExecutor();
        final ExecutorService providerWorker=Executors.newSingleThreadExecutor();
        final ExecutorService httpWorker=Executors.newSingleThreadExecutor();
        final ExecutorService caller=Executors.newSingleThreadExecutor();
        final HttpServer server;
        final CircuitBreakerRegistry registry=CircuitBreakerRegistry.ofDefaults();
        final SemanticEmbeddings semantic;
        final String profile;
        final Map<String,String> originalProperties=new HashMap<>();
        final LlmHttpClient http;
        final String endpoint;

        Fixture(boolean resetConnection) throws Exception {
            for(String key:List.of("http.nonProxyHosts","https.nonProxyHosts","socksNonProxyHosts")){
                originalProperties.put(key,System.getProperty(key));
                System.setProperty(key,"localhost|127.*|[::1]");
            }
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(serverWorker);
            server.createContext("/warmup",exchange->{
                try(exchange){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,BODY.length);exchange.getResponseBody().write(BODY);}
            });
            server.createContext("/embeddings",exchange->{
                try(exchange){
                    exchange.getRequestBody().readAllBytes();requests.incrementAndGet();
                    if(!resetConnection){
                        exchange.getResponseHeaders().set("Content-Type","application/json");
                        exchange.sendResponseHeaders(200,BODY.length);
                        exchange.getResponseBody().write(BODY,0,1);exchange.getResponseBody().flush();
                    }
                    entered.countDown();
                    if(!release.await(60,TimeUnit.SECONDS))return;
                    if(!resetConnection)exchange.getResponseBody().write(BODY,1,BODY.length-1);
                } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                  catch(java.io.IOException disconnected){/* Expected when the client cancels its read. */}
            });
            server.start();
            endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            var json=new ObjectMapper();
            var providers=mock(ProviderQueryService.class);
            String auth="{\"version\":1,\"credentialRef\":null,\"headerName\":\"Authorization\",\"prefix\":\"Bearer \"}";
            var config=new ProviderRuntimeConfig("fixture","fixture",ProviderType.OPENAI_COMPATIBLE,endpoint,auth,"fixture",true);
            when(providers.requireEnabled("fixture")).thenReturn(config);
            http=new LlmHttpClient(httpWorker);
            var breaker=new CircuitBreakerService(registry,providerWorker,Duration.ofSeconds(45),3,Duration.ofMillis(1),3,Duration.ofMillis(1));
            var service=new ProviderEmbeddingService(providers,new ProviderUrlPolicy(true,""),new ProviderAuthConfigCodec(json),
                    mock(CredentialResolver.class),http,breaker,json);
            semantic=new SemanticEmbeddings(service,json,new ExecutionLifecycle());
            profile=json.writeValueAsString(new EmbeddingProfile("fixture",ProviderType.OPENAI_COMPATIBLE,endpoint,auth,"fixture",2));
        }
        void warmup(){assertThat(http.post(endpoint+"/warmup",Map.of(),"{}")).contains("embedding");}
        @Override public void close() throws Exception {
            release.countDown();server.stop(0);
            for(var executor:List.of(caller,providerWorker,httpWorker,serverWorker))executor.shutdownNow();
            try{
                for(var executor:List.of(caller,providerWorker,httpWorker,serverWorker))
                    assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).as("fixture worker ended").isTrue();
            }finally{
                originalProperties.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            }
        }
    }
}
