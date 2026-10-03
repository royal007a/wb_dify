package com.hify.workflow.application;

import com.hify.common.ExecutionLifecycle;
import jakarta.persistence.EntityManager;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** Single-instance startup only: old process must be stopped before a new one starts. */
@Service
public class WorkflowRecovery {
    private final EntityManager entities;
    private final ExecutionLifecycle lifecycle;
    public WorkflowRecovery(EntityManager entities,ExecutionLifecycle lifecycle){this.entities=entities;this.lifecycle=lifecycle;}
    @EventListener(ApplicationReadyEvent.class) @Order(Ordered.HIGHEST_PRECEDENCE) @Transactional
    public void interruptOrphanedExecutions() {
        if(lifecycle.isStopping())return;
        // Cutoff excludes requests accepted by Tomcat during startup and before ApplicationReady.
        Instant cutoff=lifecycle.startedAt(),now=Instant.now();
        entities.createQuery("update WorkflowNodeRun n set n.status='INTERRUPTED', n.errorMessage='Previous process interrupted', n.finishedAt=:now where n.status='RUNNING' and n.workflowRunId in (select r.id from WorkflowRun r where r.createdAt < :cutoff)")
                .setParameter("now",now).setParameter("cutoff",cutoff).executeUpdate();
        entities.createQuery("update WorkflowRun r set r.status='INTERRUPTED', r.errorMessage='Previous process interrupted', r.finishedAt=:now, r.rowVersion=r.rowVersion+1 where r.status='RUNNING' and r.createdAt < :cutoff")
                .setParameter("now",now).setParameter("cutoff",cutoff).executeUpdate();
    }
}
