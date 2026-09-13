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
        return new ThreadPoolExecutor(10, 50, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100), new NamedThreadFactory("llm-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "asyncExecutor", destroyMethod = "shutdown")
    Executor asyncExecutor() {
        return new ThreadPoolExecutor(5, 20, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200), new NamedThreadFactory("async-"),
                new ThreadPoolExecutor.AbortPolicy());
    }
}

