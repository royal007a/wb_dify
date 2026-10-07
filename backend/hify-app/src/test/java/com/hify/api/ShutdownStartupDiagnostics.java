package com.hify.api;

import java.lang.management.ManagementFactory;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.core.metrics.StartupStep;

/** Opt-in test diagnostics only. No time limit, recovery or functional configuration changes. */
final class ShutdownStartupDiagnostics {
    private static final Set<String> STAGES=Set.of(
        "spring.boot.application.starting", "spring.boot.application.environment-prepared",
        "spring.boot.application.context-prepared", "spring.boot.application.context-loaded",
        "spring.boot.application.started", "spring.boot.application.ready", "spring.context.refresh",
        "spring.context.beans.post-process", "spring.beans.instantiate", "spring.beans.smart-initialize",
        "spring.data.repository.init");
    private static final Set<String> BEANS=Set.of("entityManagerFactory", "dataSource", "flyway",
        "flywayInitializer", "runApplicationService", "workflowRecovery", "knowledgeRecovery");

    static <T> T capture(boolean enabled, SpringApplicationBuilder builder, Supplier<T> action,
                         Consumer<String> output) {
        if(!enabled) return action.get();
        var startup=new BufferingApplicationStartup(2048);
        builder.applicationStartup(startup);
        long started=System.nanoTime(), cpuStarted=cpuTime();
        try {
            return action.get();
        } finally {
            // A RuntimeException from diagnostics must not replace the original result/failure.
            try {
                long elapsed=System.nanoTime()-started, cpuEnded=cpuTime();
                var events=startup.getBufferedTimeline().getEvents();
                output.accept(String.format(Locale.ROOT,
                    "[startup-detail] wallMs=%.3f currentThreadCpuMs=%.3f completedEvents=%d interrupted=%s",
                    Math.max(0,elapsed)/1_000_000d,
                    cpuStarted<0||cpuEnded<cpuStarted ? -1d : (cpuEnded-cpuStarted)/1_000_000d,
                    events.size(),Thread.currentThread().isInterrupted()));
                events.stream().sorted(Comparator.comparing(
                    (org.springframework.boot.context.metrics.buffering.StartupTimeline.TimelineEvent e)->e.getDuration()).reversed())
                    .limit(12).forEach(event->{
                        StartupStep step=event.getStartupStep();
                        String stage=STAGES.contains(step.getName())?step.getName():"other";
                        output.accept(String.format(Locale.ROOT,
                            "[startup-detail] id=%d stage=%s bean=%s durationMs=%.3f",
                            step.getId(),stage,bean(step),event.getDuration().toNanos()/1_000_000d));
                    });
            } catch(RuntimeException ignored) {
                // Never print exception text, tags, datasource URLs or arbitrary bean names.
            }
        }
    }

    private static String bean(StartupStep step) {
        for(var tag:step.getTags())
            if("beanName".equals(tag.getKey()) && BEANS.contains(tag.getValue())) return tag.getValue();
        return "other";
    }
    private static long cpuTime() {
        try {
            var threads=ManagementFactory.getThreadMXBean();
            return threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                ? threads.getCurrentThreadCpuTime() : -1;
        } catch(RuntimeException unavailable) { return -1; }
    }
}
