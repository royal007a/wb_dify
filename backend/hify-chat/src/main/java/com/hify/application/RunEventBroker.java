package com.hify.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.domain.RunEvent;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.RunEventRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Service
public class RunEventBroker {
    private static final List<String> TERMINAL = List.of("run.completed","run.failed","run.cancelled","run.needs_input");
    private static final long STREAM_TIMEOUT_MS = 180_000;
    private static final int PAGE_SIZE = 32;
    private final RunEventRepository events;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor senders;
    private final Map<String,Set<Subscription>> subscribers = new ConcurrentHashMap<>();

    @Autowired
    public RunEventBroker(RunEventRepository events, AgentRunRepository runs, ObjectMapper objectMapper,
                          @Value("${hify.sse.max-subscribers:64}") int maxSubscribers) {
        if(maxSubscribers<1||maxSubscribers>256)throw new IllegalArgumentException("SSE subscriber limit must be between 1 and 256");
        this.events=events;this.runs=runs;this.objectMapper=objectMapper;
        var number=new java.util.concurrent.atomic.AtomicInteger();
        senders=new ThreadPoolExecutor(0,maxSubscribers,30,TimeUnit.SECONDS,new SynchronousQueue<>(),job->{
            Thread thread=new Thread(job,"hify-sse-"+number.incrementAndGet());thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public RunEventBroker(RunEventRepository events,AgentRunRepository runs,ObjectMapper objectMapper) {this(events,runs,objectMapper,64);}

    @Transactional
    public RunEvent publish(String runId,String type,Map<String,Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Event publication requires a transaction");
        runs.findByIdForUpdate(runId).orElseThrow(()->new IllegalArgumentException("Run not found"));
        long sequence=events.findTopByRunIdOrderBySequenceNoDesc(runId).map(e->e.getSequenceNo()+1).orElse(1L);
        RunEvent event=events.saveAndFlush(new RunEvent(runId,sequence,type,toJson(data)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // No network I/O or extra database acquisition on the committing thread.
                signal(runId,false);
            }
        });
        return event;
    }

    /** Non-servlet test/embedded use. HTTP callers must supply response readiness below. */
    public SseEmitter subscribe(String runId,Long afterEventId) {return subscribe(runId,afterEventId,()->true);}

    public SseEmitter subscribe(String runId,Long afterEventId,BooleanSupplier responseCommitted) {
        runs.findById(runId).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND));
        if(afterEventId!=null && (afterEventId<0 || afterEventId>0 &&
                events.findById(afterEventId).filter(e->runId.equals(e.getRunId())).isEmpty()))
            throw new BizException(ErrorCode.PARAM_ERROR,"Event cursor does not belong to this run");
        SseEmitter emitter=new SseEmitter(STREAM_TIMEOUT_MS);
        Subscription sub=new Subscription(runId,emitter,afterEventId==null?0:afterEventId,responseCommitted);
        emitter.onCompletion(sub::stop);
        emitter.onTimeout(sub::stop);
        emitter.onError(error->sub.stop());
        // Only this small handshake enters Spring's pre-initialization buffer. The worker
        // waits for MVC to commit it before reading history, avoiding unbounded early buffering.
        try {emitter.send(heartbeatEvent());}
        catch(IOException failure){throw new IllegalStateException("Could not initialize event stream");}
        subscribers.compute(runId,(id,set)->{if(set==null)set=ConcurrentHashMap.newKeySet();set.add(sub);return set;});
        try {senders.execute(()->deliver(sub));}
        catch(RejectedExecutionException full){remove(sub);throw new BizException(ErrorCode.SERVICE_UNAVAILABLE,"Event stream capacity exhausted; retry with the last event ID");}
        return emitter;
    }

    @Scheduled(fixedRate=15_000L)
    void heartbeat() {for(String runId:subscribers.keySet())signal(runId,true);}
    private void signal(String runId,boolean heartbeat) {
        for(Subscription sub:subscribers.getOrDefault(runId,Set.of()))sub.signal(heartbeat);
    }

    private void deliver(Subscription sub) {
        try {
            long initializeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!sub.closed&&!sub.responseCommitted.getAsBoolean()) {
                if(System.nanoTime()>=initializeDeadline)return;
                Thread.sleep(10);
            }
            while(!sub.closed&&System.nanoTime()<sub.deadline) {
                boolean heartbeat=sub.awaitSignal();
                if(sub.closed||System.nanoTime()>=sub.deadline)break;
                drain(sub);
                if(heartbeat&&!sub.closed&&System.nanoTime()<sub.deadline)sub.emitter.send(heartbeatEvent());
            }
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException disconnected) {
            org.slf4j.LoggerFactory.getLogger(RunEventBroker.class).debug("SSE subscription closed; runId={}",sub.runId);
        } finally {
            remove(sub);
            // Only the owning sender calls complete; never a commit or shared heartbeat thread.
            sub.emitter.complete();
        }
    }

    private void drain(Subscription sub) throws IOException {
        if(events.findFirstByRunIdAndEventTypeInOrderByIdAsc(sub.runId,TERMINAL)
                .filter(e->e.getId()<=sub.cursor).isPresent()){sub.stop();return;}
        while(!sub.closed&&System.nanoTime()<sub.deadline) {
            // Repository calls end before sending. Bounded pages, no per-event delivery queue.
            List<RunEvent> page=events.findByRunIdAndIdGreaterThanOrderByIdAsc(sub.runId,sub.cursor,PageRequest.of(0,PAGE_SIZE));
            for(RunEvent event:page) {
                if(sub.closed||System.nanoTime()>=sub.deadline)return;
                sub.emitter.send(SseEmitter.event().id(event.getId().toString()).name(event.getEventType()).data(event.getPayload(),MediaType.APPLICATION_JSON));
                sub.cursor=event.getId();
                if(TERMINAL.contains(event.getEventType())){sub.stop();return;}
            }
            if(page.size()<PAGE_SIZE)return;
        }
    }
    private SseEmitter.SseEventBuilder heartbeatEvent(){return SseEmitter.event().name("heartbeat").data(Map.of("at",Instant.now().toString()),MediaType.APPLICATION_JSON);}
    private void remove(Subscription sub){sub.stop();subscribers.computeIfPresent(sub.runId,(id,set)->{set.remove(sub);return set.isEmpty()?null:set;});}
    @PreDestroy public void close(){for(var set:subscribers.values())for(var sub:set)sub.stop();senders.shutdownNow();}
    private String toJson(Map<String,Object> data){try{return objectMapper.writeValueAsString(data);}catch(JsonProcessingException failure){throw new IllegalStateException("Could not serialize run event",failure);}}

    private static final class Subscription {
        final String runId;
        final SseEmitter emitter;
        final BooleanSupplier responseCommitted;
        final long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(STREAM_TIMEOUT_MS);
        volatile boolean closed;
        long cursor;
        private boolean dirty=true,heartbeat;
        Subscription(String runId,SseEmitter emitter,long cursor,BooleanSupplier responseCommitted){this.runId=runId;this.emitter=emitter;this.cursor=cursor;this.responseCommitted=responseCommitted;}
        synchronized void signal(boolean heartbeat){dirty=true;this.heartbeat|=heartbeat;notifyAll();}
        synchronized void stop(){closed=true;notifyAll();}
        synchronized boolean awaitSignal() throws InterruptedException {
            while(!closed&&!dirty){long nanos=deadline-System.nanoTime();if(nanos<=0)return false;TimeUnit.NANOSECONDS.timedWait(this,nanos);}
            boolean value=heartbeat;heartbeat=false;dirty=false;return value;
        }
    }
}
