package com.hify.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ThreadPoolConfig {
    @Bean(name = "llmExecutor", destroyMethod = "shutdown")
    Executor llmExecutor() {
        return correlatedPool(10, 50, 100, "llm-");
    }

    @Bean(name = "asyncExecutor", destroyMethod = "shutdown")
    Executor asyncExecutor() {
        return correlatedPool(5, 20, 200, "async-");
    }

    private ThreadPoolExecutor correlatedPool(int core, int max, int capacity, String prefix) {
        return new ThreadPoolExecutor(core, max, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(capacity), new NamedThreadFactory(prefix),
                // Running blocking HTTP on the waiting caller disables cancellation/deadline polling.
                new ThreadPoolExecutor.AbortPolicy()) {
            @Override public void execute(Runnable task) {
                super.execute(RequestLogContext.wrap(task));
            }
        };
    }
}
