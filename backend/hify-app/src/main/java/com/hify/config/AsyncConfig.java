package com.hify.config;

import com.hify.common.RequestLogContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
public class AsyncConfig {
    @Bean(name = "runExecutor")
    @DependsOn("entityManagerFactory")
    Executor runExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("hify-run-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setTaskDecorator(RequestLogContext::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // Admission is closed by ExecutionLifecycle before this late shutdown. Interrupt and
        // join while persistence is still alive, instead of waiting in the graceful stop phase.
        executor.setAcceptTasksAfterContextClose(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
