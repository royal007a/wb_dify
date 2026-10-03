package com.hify.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.RunEvent;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.RunEventRepository;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RunEventBroker {
    private static final List<String> TERMINAL = List.of("run.completed","run.failed","run.cancelled","run.needs_input");
    private final RunEventRepository events;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    // Stable locks are never removed at terminal state; bounded memory, no double-lock window.
    private final Object[] locks = new Object[64];
    private final Map<String,List<Subscription>> subscribers = new ConcurrentHashMap<>();

    public RunEventBroker(RunEventRepository events, AgentRunRepository runs, ObjectMapper objectMapper) {
        this.events=events;this.runs=runs;this.objectMapper=objectMapper;
        Arrays.setAll(locks, ignored -> new Object());
    }

    @Transactional
    public RunEvent publish(String runId,String type,Map<String,Object> data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Event publication requires a transaction");
        // Database lock covers allocation through commit, including multiple JVM writers.
        runs.findByIdForUpdate(runId).orElseThrow(()->new IllegalArgumentException("Run not found: "+runId));
        long sequence=events.findTopByRunIdOrderBySequenceNoDesc(runId).map(e->e.getSequenceNo()+1).orElse(1L);
        RunEvent event=events.saveAndFlush(new RunEvent(runId,sequence,type,toJson(data)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Callbacks can race; drain persisted records instead of emitting this event directly.
                try { drain(runId); }
                catch (RuntimeException projectionFailure) {
                    // Heartbeat/reconnect recovers delivery; do not fail an already committed operation.
                    org.slf4j.LoggerFactory.getLogger(RunEventBroker.class).warn("SSE projection deferred; eventId={}",event.getId());
                }
            }
        });
        return event;
    }

    public SseEmitter subscribe(String runId,Long afterEventId) {
        runs.findById(runId).orElseThrow(()->new IllegalArgumentException("Run not found: "+runId));
        if(afterEventId!=null&&afterEventId<0)throw new IllegalArgumentException("Event cursor cannot be negative");
        SseEmitter emitter=new SseEmitter(180_000L);
        Subscription sub=new Subscription(emitter,afterEventId==null?0:afterEventId);
        emitter.onCompletion(()->remove(runId,sub));
        emitter.onTimeout(()->remove(runId,sub));
        emitter.onError(error->remove(runId,sub));
        synchronized(lock(runId)) {
            subscribers.computeIfAbsent(runId,ignored->new ArrayList<>()).add(sub);
            try {
                drainLocked(runId);
                // A terminal row alone cannot close the stream before its event is committed.
                if(subscribers.getOrDefault(runId,List.of()).contains(sub))
                    events.findFirstByRunIdAndEventTypeInOrderByIdAsc(runId,TERMINAL)
                            .filter(e->e.getId()<=sub.cursor).ifPresent(e->complete(runId,sub));
            } catch(RuntimeException failure) { remove(runId,sub);emitter.completeWithError(failure); }
        }
        return emitter;
    }

    @Scheduled(fixedRate=15_000L)
    void heartbeat() {
        for(String runId:List.copyOf(subscribers.keySet())) synchronized(lock(runId)) {
            try { drainLocked(runId); }
            catch(RuntimeException unavailable) { continue; }
            for(Subscription sub:List.copyOf(subscribers.getOrDefault(runId,List.of()))) {
                try { sub.emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("at",Instant.now().toString()),MediaType.APPLICATION_JSON)); }
                catch(IOException|IllegalStateException closed) { remove(runId,sub); }
            }
        }
    }

    private void drain(String runId) { synchronized(lock(runId)){drainLocked(runId);} }
    private void drainLocked(String runId) {
        List<Subscription> current=List.copyOf(subscribers.getOrDefault(runId,List.of()));
        if(current.isEmpty())return;
        long cursor=current.stream().mapToLong(s->s.cursor).min().orElse(0);
        List<RunEvent> pending=events.findByRunIdAndIdGreaterThanOrderByIdAsc(runId,cursor);
        for(Subscription sub:current) for(RunEvent event:pending) {
            if(event.getId()<=sub.cursor)continue;
            try {
                sub.emitter.send(SseEmitter.event().id(event.getId().toString()).name(event.getEventType()).data(event.getPayload(),MediaType.APPLICATION_JSON));
                sub.cursor=event.getId();
                if(TERMINAL.contains(event.getEventType())) {complete(runId,sub);break;}
            } catch(IOException|IllegalStateException closed) {remove(runId,sub);break;}
        }
    }
    private Object lock(String runId){return locks[Math.floorMod(runId.hashCode(),locks.length)];}
    private void complete(String runId,Subscription sub){remove(runId,sub);sub.emitter.complete();}
    private void remove(String runId,Subscription sub){synchronized(lock(runId)){List<Subscription> list=subscribers.get(runId);if(list!=null){list.remove(sub);if(list.isEmpty())subscribers.remove(runId);}}}
    private String toJson(Map<String,Object> data){try{return objectMapper.writeValueAsString(data);}catch(JsonProcessingException failure){throw new IllegalStateException("Could not serialize run event",failure);}}
    private static final class Subscription {final SseEmitter emitter;long cursor;Subscription(SseEmitter emitter,long cursor){this.emitter=emitter;this.cursor=cursor;}}
}
