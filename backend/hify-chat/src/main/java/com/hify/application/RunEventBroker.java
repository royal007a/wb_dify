package com.hify.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.domain.AgentRun;
import com.hify.domain.RunEvent;
import com.hify.infra.AgentRunRepository;
import com.hify.infra.RunEventRepository;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class RunEventBroker {
    private final RunEventRepository events;
    private final AgentRunRepository runs;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public RunEventBroker(RunEventRepository events, AgentRunRepository runs, ObjectMapper objectMapper) {
        this.events = events;
        this.runs = runs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RunEvent publish(String runId, String type, Map<String, Object> data) {
        Object lock = locks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock) {
            long sequence = events.findTopByRunIdOrderBySequenceNoDesc(runId)
                    .map(event -> event.getSequenceNo() + 1).orElse(1L);
            RunEvent event = events.saveAndFlush(new RunEvent(runId, sequence, type, toJson(data)));
            emit(runId, event);
            if (type.equals("run.completed") || type.equals("run.failed")
                    || type.equals("run.cancelled") || type.equals("run.needs_input")) {
                complete(runId);
            }
            return event;
        }
    }

    public SseEmitter subscribe(String runId, Long afterEventId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        SseEmitter emitter = new SseEmitter(180_000L);
        Object lock = locks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock) {
            List<RunEvent> replay = afterEventId == null
                    ? events.findByRunIdOrderByIdAsc(runId)
                    : events.findByRunIdAndIdGreaterThanOrderByIdAsc(runId, afterEventId);
            try {
                for (RunEvent event : replay) send(emitter, event);
                if (run.getState().terminal()) {
                    emitter.complete();
                    return emitter;
                }
                subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            } catch (IOException exception) {
                emitter.completeWithError(exception);
                return emitter;
            }
        }
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        return emitter;
    }

    @Scheduled(fixedRate = 15_000L)
    void heartbeat() {
        subscribers.forEach((runId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat")
                        .data("{\"at\":\"" + Instant.now() + "\"}", MediaType.APPLICATION_JSON));
            } catch (IOException exception) {
                remove(runId, emitter);
            }
        }));
    }

    private void emit(String runId, RunEvent event) {
        subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).forEach(emitter -> {
            try {
                send(emitter, event);
            } catch (IOException exception) {
                remove(runId, emitter);
            }
        });
    }

    private void send(SseEmitter emitter, RunEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getId()))
                .name(event.getEventType())
                .data(event.getPayload(), MediaType.APPLICATION_JSON));
    }

    private void complete(String runId) {
        subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).forEach(SseEmitter::complete);
        subscribers.remove(runId);
        locks.remove(runId);
    }

    private void remove(String runId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(runId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) subscribers.remove(runId);
        }
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize run event", exception);
        }
    }
}
