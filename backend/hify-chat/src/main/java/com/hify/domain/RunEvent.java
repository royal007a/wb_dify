package com.hify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "run_events", uniqueConstraints =
        @UniqueConstraint(name = "uq_run_event_sequence", columnNames = {"run_id", "sequence_no"}))
public class RunEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String runId;
    @Column(name = "sequence_no")
    private long sequenceNo;
    private String eventType;
    @Column(length = 20000)
    private String payload;
    private Instant createdAt;

    protected RunEvent() {}

    public RunEvent(String runId, long sequenceNo, String eventType, String payload) {
        this.runId = runId;
        this.sequenceNo = sequenceNo;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public long getSequenceNo() { return sequenceNo; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
