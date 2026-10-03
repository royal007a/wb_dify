package com.hify.common;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** One process/context epoch; never a user cancellation or a distributed lease. */
@Component
public final class ExecutionLifecycle implements ApplicationListener<ContextClosedEvent>, ApplicationContextAware, Ordered {
    private final Instant startedAt = Instant.now();
    private volatile boolean stopping;
    private ApplicationContext context;
    public boolean isStopping() { return stopping; }
    public Instant startedAt() { return startedAt; }
    @Override public void setApplicationContext(ApplicationContext context) { this.context = context; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == context) stopping = true;
    }
}
